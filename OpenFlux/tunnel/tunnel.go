package tunnel

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"

	"openflux/network"
	"openflux/transport"
	"openflux/tunnel/l3"
	"openflux/utils"
)

// ExitMode выбирает, как выходная нода общается с интернетом.
type ExitMode int

const (
	ExitModeL3 ExitMode = iota
	ExitModeL4
)

func (m ExitMode) String() string {
	switch m {
	case ExitModeL3:
		return "l3"
	default:
		return "l4"
	}
}

func ParseExitMode(s string) (ExitMode, error) {
	switch s {
	case "", "l4", "proxy":
		return ExitModeL4, nil
	case "l3":
		return ExitModeL3, nil
	default:
		return ExitModeL4, fmt.Errorf("unknown mode %q (want l3|l4)", s)
	}
}

type TCPTunnel struct {
	gvisorStack *stack.Stack
	tunnelEP    *TunnelLinkEndpoint
	transport   transport.Transport
	isExitNode  bool
	exitMode    ExitMode
	startTime   time.Time
	packetCount atomic.Uint64
	stopOnce    sync.Once
	stopCh      chan struct{}
	udpFlows    atomic.Int32
	tcpFlows    atomic.Int32
	// allowAnyDest is a test seam. An end-to-end test needs a reachable
	// destination on the machine it runs on, and every local address is one the
	// exit filter has to refuse - loopback, or the host's own. Production code
	// never sets it; only the in-package tests do.
	allowAnyDest bool
	// seen guards the TCP stack against the carrier's at-least-once delivery.
	seen *dedupe
}

var (
	TCPBufMin     = 4 * 1024 * 1024
	TCPBufDefault = 16 * 1024 * 1024
	TCPBufMax     = 64 * 1024 * 1024
)

func SetTCPBuffers(s *stack.Stack) {
	rcv := tcpip.TCPReceiveBufferSizeRangeOption{Min: TCPBufMin, Default: TCPBufDefault, Max: TCPBufMax}
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &rcv); err != nil {
		utils.Debugf("[TUNNEL] set recv buffer: %v", err)
	}
	snd := tcpip.TCPSendBufferSizeRangeOption{Min: TCPBufMin, Default: TCPBufDefault, Max: TCPBufMax}
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &snd); err != nil {
		utils.Debugf("[TUNNEL] set send buffer: %v", err)
	}
}

func NewTCPTunnel(trans transport.Transport, isExitNode bool) *TCPTunnel {
	return NewTCPTunnelMode(trans, isExitNode, ExitModeL4)
}

func NewTCPTunnelMode(trans transport.Transport, isExitNode bool, mode ExitMode) *TCPTunnel {
	t := &TCPTunnel{
		transport:  trans,
		isExitNode: isExitNode,
		exitMode:   mode,
		startTime:  time.Now(),
		stopCh:     make(chan struct{}),
		seen:       newDedupe(),
	}

	utils.Debugf("[TUNNEL] Net stack init...")
	t.gvisorStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	SetTCPBuffers(t.gvisorStack)

	tunnelEP := NewTunnelLinkEndpoint()
	if n, ok := trans.(transport.PeerParameterProvider); ok {
		if p, ready := n.PeerParameters(); ready {
			tunnelEP.SetMTU(uint32(min(1500, p.MaxPacketSize)))
		}
	}
	// "->" points towards the internet and "<-" back towards the device on
	// both sides, as in the L3 and utun logs, so one flow reads the same in
	// the client's and the exit's log.
	toPeer, fromPeer := network.DirOutbound, network.DirInbound
	if isExitNode {
		toPeer, fromPeer = network.DirInbound, network.DirOutbound
	}
	tunnelEP.onOutgoingPacket = func(data []byte) {
		t.packetCount.Add(1)
		network.LogPacket("TUNNEL", toPeer, data)
		if err := trans.Send(data); err != nil {
			// Packetf, not Debugf, for the same reason the batching layer's
			// per-batch lines are: mobile.Start() turns debug logging on for
			// the whole session, every Debugf goes to os.Stderr (ERROR
			// priority in logcat) and to the app's log sink, and that sink
			// takes the same mutex the packet path uses. A degraded tunnel
			// failing to send once per packet therefore turns into a storm of
			// logging that contends with the packets it is complaining about -
			// the failure makes the tunnel slower.
			utils.Packetf("[TUNNEL] trans.Send error: %v", err)
		}
	}
	t.tunnelEP = tunnelEP

	tunnelNIC := tcpip.NICID(1)
	if err := t.gvisorStack.CreateNIC(tunnelNIC, tunnelEP); err != nil {
		utils.Debugf("[TUNNEL] CreateNIC tunnel error: %v", err)
	}

	if isExitNode {
		t.setupExitNodeProxy(tunnelNIC)
	} else {
		t.setupClient(tunnelNIC)
	}

	trans.Receive(func(data []byte) {
		// The carrier is at-least-once; a copy that reaches the TCP stack
		// resets the connection it belongs to. See dedupe.go for the capture
		// from both ends that shows it.
		if t.seen.duplicate(data, time.Now()) {
			utils.Debugf("[TUNNEL] dropped duplicate packet of %d bytes (%d so far)", len(data), t.seen.droppedCount())
			return
		}
		network.LogPacket("TUNNEL", fromPeer, data)
		tunnelEP.InjectInbound(data)
	})

	utils.SafeGo("tunnel.printStats", t.printStats)
	return t
}

func (t *TCPTunnel) setupExitNodeProxy(tunnelNIC tcpip.NICID) {
	utils.Debugf("[TUNNEL] EXIT NODE - proxy mode (no raw sockets)")

	t.gvisorStack.SetPromiscuousMode(tunnelNIC, true)
	t.gvisorStack.SetSpoofing(tunnelNIC, true)
	t.gvisorStack.AddRoute(tcpip.Route{
		Destination: header.IPv4EmptySubnet,
		NIC:         tunnelNIC,
	})

	fwd := tcp.NewForwarder(t.gvisorStack, 0, 8192, t.handleExitTCP)
	t.gvisorStack.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)
	udpFwd := udp.NewForwarder(t.gvisorStack, t.handleExitUDP)
	t.gvisorStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
}

const udpIdleTimeout = 2 * time.Minute

func (t *TCPTunnel) handleExitUDP(r *udp.ForwarderRequest) bool {
	if t.udpFlows.Add(1) > 256 {
		t.udpFlows.Add(-1)
		return true
	}
	id := r.ID()
	dest := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprintf("%d", id.LocalPort))

	if !t.allowAnyDest && !exitDestinationAllowed(id.LocalAddress) {
		utils.Debugf("[EXIT] refused %s: blocked destination on an exit node", dest)
		t.udpFlows.Add(-1)
		return true
	}

	var wq waiter.Queue
	ep, tErr := r.CreateEndpoint(&wq)
	if tErr != nil {
		t.udpFlows.Add(-1)
		utils.Debugf("[EXIT] UDP CreateEndpoint %s: %v", dest, tErr)
		return true
	}
	local := gonet.NewUDPConn(&wq, ep)
	remote, err := net.DialTimeout("udp", dest, 10*time.Second)
	if err != nil {
		utils.Debugf("[EXIT] UDP dial %s failed: %v", dest, err)
		local.Close()
		t.udpFlows.Add(-1)
		return true
	}

	utils.SafeGo("exit.udp-flow", func() {
		defer t.udpFlows.Add(-1)
		refresh := func() {
			deadline := time.Now().Add(udpIdleTimeout)
			_ = local.SetReadDeadline(deadline)
			_ = remote.SetReadDeadline(deadline)
		}
		refresh()
		var once sync.Once
		closeBoth := func() {
			_ = local.Close()
			_ = remote.Close()
		}
		pump := func(dst, src net.Conn) {
			defer once.Do(closeBoth)
			buf := make([]byte, 65535)
			for {
				n, err := src.Read(buf)
				if err != nil {
					return
				}
				refresh()
				_ = dst.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if _, err := dst.Write(buf[:n]); err != nil {
					return
				}
			}
		}
		done := make(chan struct{})
		go func() { defer close(done); pump(remote, local) }()
		pump(local, remote)
		<-done
	})
	return true
}

// maxExitTCPFlows bounds concurrent forwarded TCP connections on an exit node.
// A peer can synthesise SYNs without opening anything: gVisor checks the
// checksum, the SYN bit and that no SYN-ACK came back, all of which the packet
// controls. Each accepted flow costs a goroutine, an outbound socket held for
// up to the dial timeout, and two 256 KiB copy buffers.
const maxExitTCPFlows = 1024

func (t *TCPTunnel) handleExitTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dest := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))

	if !t.allowAnyDest && !exitDestinationAllowed(id.LocalAddress) {
		utils.Debugf("[EXIT] refused %s: blocked destination on an exit node", dest)
		r.Complete(true)
		return
	}
	if t.tcpFlows.Add(1) > maxExitTCPFlows {
		t.tcpFlows.Add(-1)
		utils.Debugf("[EXIT] refused %s: over the %d concurrent flow limit", dest, maxExitTCPFlows)
		r.Complete(true)
		return
	}

	var wq waiter.Queue
	ep, tErr := r.CreateEndpoint(&wq)
	if tErr != nil {
		t.tcpFlows.Add(-1)
		utils.Debugf("[EXIT] CreateEndpoint %s: %v", dest, tErr)
		r.Complete(true)
		return
	}
	r.Complete(false)
	local := gonet.NewTCPConn(&wq, ep)

	utils.SafeGo("exit.flow", func() {
		defer t.tcpFlows.Add(-1)
		remote, err := net.DialTimeout("tcp", dest, 10*time.Second)
		if err != nil {
			utils.Debugf("[EXIT] dial %s failed: %v", dest, err)
			local.Close()
			return
		}
		if tc, ok := remote.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
			_ = tc.SetReadBuffer(16 * 1024 * 1024)
			_ = tc.SetWriteBuffer(16 * 1024 * 1024)
		}
		utils.Debugf("[EXIT] %s connected", dest)

		go func() {
			buf := make([]byte, 256*1024)
			io.CopyBuffer(remote, local, buf)
			remote.Close()
			local.Close()
		}()
		buf := make([]byte, 256*1024)
		io.CopyBuffer(local, remote, buf)
		local.Close()
		remote.Close()
	})
}

func (t *TCPTunnel) setupClient(tunnelNIC tcpip.NICID) {
	clientAddr := tcpip.AddrFrom4([4]byte{10, 10, 10, 2})
	t.gvisorStack.AddProtocolAddress(tunnelNIC, tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   clientAddr,
			PrefixLen: 24,
		},
	}, stack.AddressProperties{})

	t.gvisorStack.AddRoute(tcpip.Route{
		Destination: header.IPv4EmptySubnet,
		NIC:         tunnelNIC,
	})
}

var dialTimeout = 10 * time.Second

func (t *TCPTunnel) DialTCP(address string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(address)
	if err != nil {
		return nil, fmt.Errorf("split address: %w", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 0 || port > 65535 {
		return nil, fmt.Errorf("bad port in %q", address)
	}

	ip, err := t.resolveIPv4(host)
	if err != nil {
		return nil, err
	}
	utils.Debugf("[TUNNEL] DialTCP %s -> %s:%d", address, ip.String(), port)

	nic := tcpip.NICID(1)
	if t.isExitNode && false {
		nic = tcpip.NICID(2)
	}

	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()
	conn, err := gonet.DialContextTCP(ctx, t.gvisorStack, tcpip.FullAddress{
		NIC:  nic,
		Addr: tcpip.AddrFrom4([4]byte{ip[0], ip[1], ip[2], ip[3]}),
		Port: uint16(port),
	}, ipv4.ProtocolNumber)
	if err != nil {
		return nil, err
	}
	return conn, nil
}

// resolveIPv4 resolves host to an IPv4 address using the local system
// resolver (for a literal IP this is just a parse, no lookup).
func (t *TCPTunnel) resolveIPv4(host string) (net.IP, error) {
	if literal := net.ParseIP(host); literal != nil {
		if ip4 := literal.To4(); ip4 != nil {
			return ip4, nil
		}
		return nil, fmt.Errorf("IPv6 not supported")
	}

	tcpAddr, err := net.ResolveTCPAddr("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return nil, fmt.Errorf("resolve: %w", err)
	}
	ip4 := tcpAddr.IP.To4()
	if ip4 == nil {
		return nil, fmt.Errorf("IPv6 not supported")
	}
	return ip4, nil
}

func (t *TCPTunnel) DialUDP(address string) (net.Conn, error) {
	udpAddr, err := net.ResolveUDPAddr("udp", address)
	if err != nil {
		return nil, fmt.Errorf("resolve UDP: %w", err)
	}
	ip := udpAddr.IP.To4()
	if ip == nil {
		return nil, fmt.Errorf("IPv6 not supported")
	}
	remote := &tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom4([4]byte{ip[0], ip[1], ip[2], ip[3]}),
		Port: uint16(udpAddr.Port),
	}
	return gonet.DialUDP(t.gvisorStack, nil, remote, ipv4.ProtocolNumber)
}

func (t *TCPTunnel) ListenTCP(port uint16) (net.Listener, error) {
	return gonet.ListenTCP(t.gvisorStack, tcpip.FullAddress{
		NIC:  1,
		Port: port,
	}, ipv4.ProtocolNumber)
}

func (t *TCPTunnel) Close() {
	t.stopOnce.Do(func() {
		close(t.stopCh)
		t.gvisorStack.Close()
	})
}

func (t *TCPTunnel) printStats() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-t.stopCh:
			return
		case <-ticker.C:
			stats := t.gvisorStack.Stats()
			utils.Debugf("[STATS] uptime=%v mode=%s packets=%d connected=%d established=%d retrans=%d",
				time.Since(t.startTime).Round(time.Second),
				t.exitMode.String(),
				t.packetCount.Load(),
				stats.TCP.CurrentConnected.Value(),
				stats.TCP.CurrentEstablished.Value(),
				stats.TCP.Retransmits.Value(),
			)
		}
	}
}

func SetLocalIP(ip string) error { return l3.SetLocalIP(ip) }
