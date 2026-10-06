//go:build linux

package l3

import (
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"syscall"

	"openflux/utils"
)

// rawSendErrors counts every failed sendto, including the ones the sampler
// does not print. Without it a rate-limited log cannot show that the rate
// itself changed.
var rawSendErrors atomic.Uint64

type rawBackend struct {
	sendFd  int
	recvFds []int
	egress  [4]byte

	closeOnce sync.Once
	closed    chan struct{}
	recvMu    sync.Mutex
	fdMu      sync.RWMutex
}

func newBackend() (L3Backend, error) {
	localIPMu.Lock()
	egress := localIPOverride
	hasOverride := hasLocalIPOverride
	localIPMu.Unlock()
	if !hasOverride {
		var err error
		egress, err = detectEgressIPv4()
		if err != nil {
			return nil, err
		}
	}

	sendFd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_RAW, syscall.IPPROTO_RAW)
	if err != nil {
		return nil, fmt.Errorf("l3: send socket: %w (need root or CAP_NET_RAW)", err)
	}
	if err := syscall.SetsockoptInt(sendFd, syscall.IPPROTO_IP, syscall.IP_HDRINCL, 1); err != nil {
		syscall.Close(sendFd)
		return nil, fmt.Errorf("l3: IP_HDRINCL: %w", err)
	}
	// Large send buffer: SOCK_RAW with IP_HDRINCL does not get kernel
	// auto-tuning, so the default (208 KiB) caps BDP and causes drops
	// at RTT ~100ms and >30 Mbps.
	syscall.SetsockoptInt(sendFd, syscall.SOL_SOCKET, syscall.SO_SNDBUF, 16*1024*1024)

	var recvFds []int
	for _, proto := range []int{syscall.IPPROTO_TCP, syscall.IPPROTO_UDP, syscall.IPPROTO_ICMP} {
		recvFd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_RAW, proto)
		if err != nil {
			for _, fd := range recvFds {
				syscall.Close(fd)
			}
			syscall.Close(sendFd)
			return nil, fmt.Errorf("l3: recv socket protocol %d: %w (need root or CAP_NET_RAW)", proto, err)
		}
		syscall.SetsockoptInt(recvFd, syscall.SOL_SOCKET, syscall.SO_RCVBUF, 16*1024*1024)
		recvFds = append(recvFds, recvFd)
		// close(2) alone does not reliably wake a blocking recvfrom on Linux.
		if err := syscall.SetsockoptTimeval(recvFd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &syscall.Timeval{Sec: 1}); err != nil {
			for _, fd := range recvFds {
				_ = syscall.Close(fd)
			}
			_ = syscall.Close(sendFd)
			return nil, fmt.Errorf("l3: receive timeout: %w", err)
		}
	}
	if err := syscall.SetsockoptTimeval(sendFd, syscall.SOL_SOCKET, syscall.SO_SNDTIMEO, &syscall.Timeval{Sec: 1}); err != nil {
		for _, fd := range recvFds {
			_ = syscall.Close(fd)
		}
		_ = syscall.Close(sendFd)
		return nil, fmt.Errorf("l3: send timeout: %w", err)
	}

	b := &rawBackend{
		sendFd:  sendFd,
		recvFds: recvFds,
		egress:  egress,
		closed:  make(chan struct{}),
	}
	utils.Debugf("[L3/linux] raw backend ready, egress=%s", ipStr(ipU32(egress)))
	return b, nil
}

func (b *rawBackend) EgressIP() [4]byte { return b.egress }

func (b *rawBackend) Send(pkt []byte) error {
	var ok bool
	pkt, ok = sliceIPv4(pkt)
	if !ok {
		return fmt.Errorf("l3: invalid IPv4 packet")
	}
	b.fdMu.RLock()
	defer b.fdMu.RUnlock()
	select {
	case <-b.closed:
		return net.ErrClosed
	default:
	}
	var dst [4]byte
	copy(dst[:], pkt[16:20])
	addr := &syscall.SockaddrInet4{Addr: dst}
	err := syscall.Sendto(b.sendFd, pkt, 0, addr)
	if err == syscall.EMSGSIZE {
		return &PacketTooBigError{MTU: b.routeMTU(dst)}
	}
	if err != nil {
		// A bare "operation not permitted" says nothing about which of the
		// half-dozen reasons the kernel had. On one node there were 1664 of
		// these in 25 minutes, every one of them a bare 40-byte TCP RST, and
		// none of them explained by anything the code does.
		//
		// Log-only: no control flow below changes. Sampled rather than logged
		// whole - at ~1/second a real failure would drown the tunnel in text
		// and the rate limiter in the log layer would start eating the lines
		// that matter. The running total is reported so a burst is still
		// visible as a burst.
		n := rawSendErrors.Add(1)
		if n <= 20 || n%200 == 0 {
			src := net.IP(pkt[12:16]).String()
			dstIP := net.IP(pkt[16:20]).String()
			ihl := int(pkt[0]&0x0f) * 4
			ipOK := onesComplementSum(pkt[:ihl]) == 0
			flags := "-"
			if len(pkt) >= ihl+14 {
				flags = fmt.Sprintf("0x%02x", pkt[ihl+13])
			}
			utils.Debugf("[L3] raw sendto %s->%s len=%d proto=%d flags=%s ipck=%v: %v (total %d)",
				src, dstIP, len(pkt), pkt[9], flags, ipOK, err, n)
		}
	}
	return err
}

// A connected UDP socket queries the kernel route without sending any probe.
func (b *rawBackend) routeMTU(dst [4]byte) int {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, syscall.IPPROTO_UDP)
	if err != nil {
		return 0
	}
	defer syscall.Close(fd)
	if syscall.Bind(fd, &syscall.SockaddrInet4{Addr: b.egress}) != nil {
		return 0
	}
	if syscall.Connect(fd, &syscall.SockaddrInet4{Addr: dst, Port: 9}) != nil {
		return 0
	}
	mtu, err := syscall.GetsockoptInt(fd, syscall.IPPROTO_IP, syscall.IP_MTU)
	if err != nil {
		return 0
	}
	return mtu
}

func (b *rawBackend) Recv(cb func([]byte)) {
	for _, fd := range b.recvFds {
		// SafeGo, not a bare `go`. These three loops are the whole inbound path
		// of an l3 exit node: every packet every client of that node sends
		// arrives on one of them. A bare goroutine meant that a panic anywhere
		// in decoding, SNAT or conntrack killed the process, and with it every
		// client, until systemd restarted it five seconds later. The stack is
		// now logged at the normal level, so the next occurrence is a bug that
		// can be found rather than an outage nobody can explain.
		utils.SafeGo(fmt.Sprintf("l3.recvLoop.fd%d", fd), func() { b.recvLoop(fd, cb) })
	}
}

func (b *rawBackend) recvLoop(fd int, cb func([]byte)) {
	buf := make([]byte, 65535)
	for {
		b.fdMu.RLock()
		select {
		case <-b.closed:
			b.fdMu.RUnlock()
			return
		default:
		}
		n, _, err := syscall.Recvfrom(fd, buf, 0)
		b.fdMu.RUnlock()
		if err != nil {
			if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
				continue
			}
			select {
			case <-b.closed:
				return
			default:
			}
			utils.Debugf("[L3/linux] recv: %v", err)
			continue
		}
		if n < 28 || buf[0]>>4 != 4 || (buf[9] != 6 && buf[9] != 17 && buf[9] != 1) {
			continue
		}
		if buf[16] != b.egress[0] || buf[17] != b.egress[1] ||
			buf[18] != b.egress[2] || buf[19] != b.egress[3] {
			continue
		}
		cp := make([]byte, n)
		copy(cp, buf[:n])
		b.recvMu.Lock()
		cb(cp)
		b.recvMu.Unlock()
	}
}

func (b *rawBackend) Close() error {
	b.closeOnce.Do(func() {
		close(b.closed)
		b.fdMu.Lock()
		defer b.fdMu.Unlock()
		syscall.Close(b.sendFd)
		for _, fd := range b.recvFds {
			syscall.Close(fd)
		}
	})
	return nil
}

func detectEgressIPv4() ([4]byte, error) {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return [4]byte{}, fmt.Errorf("l3: detect egress: %w", err)
	}
	defer conn.Close()
	ip := conn.LocalAddr().(*net.UDPAddr).IP.To4()
	if ip == nil {
		return [4]byte{}, fmt.Errorf("l3: no IPv4 egress address")
	}
	var out [4]byte
	copy(out[:], ip)
	return out, nil
}
