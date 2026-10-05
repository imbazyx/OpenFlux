package tunnel

import (
	"net"
	"sync"

	"gvisor.dev/gvisor/pkg/tcpip"

	"openflux/tunnel/l3"
)

// The peer chooses where the traffic goes. An exit node must not become the
// way into the machine it runs on: 127.0.0.1, where the core opens a SOCKS5
// proxy with no authentication, and 169.254.169.254, where a cloud instance
// hands out its credentials. Private ranges stay reachable on purpose — sharing
// a LAN is a normal thing to want from an exit node.
var (
	egressOnce sync.Once
	egressAddr [4]byte
)

// exitDestinationAllowed reports whether an exit node may forward to addr.
func exitDestinationAllowed(addr tcpip.Address) bool {
	var dst, self [4]byte
	copy(dst[:], addr.AsSlice())
	egressOnce.Do(func() { egressAddr = detectEgressIPv4() })
	self = egressAddr
	return !l3.BlockedDestination(dst, self)
}

// detectEgressIPv4 asks the routing table which source address the kernel
// would use, without sending anything. A connected UDP socket does this: it
// picks a route at connect time and writes nothing until a datagram is sent.
// The zero value means "not found", and the caller then skips only the
// self-comparison; loopback and link-local are still refused.
func detectEgressIPv4() [4]byte {
	c, err := net.Dial("udp4", "1.1.1.1:53")
	if err != nil {
		return [4]byte{}
	}
	defer c.Close()
	host, _, err := net.SplitHostPort(c.LocalAddr().String())
	if err != nil {
		return [4]byte{}
	}
	ip := net.ParseIP(host).To4()
	if ip == nil {
		return [4]byte{}
	}
	var out [4]byte
	copy(out[:], ip)
	return out
}
