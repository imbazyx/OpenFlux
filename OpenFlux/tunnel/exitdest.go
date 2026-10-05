package tunnel

import (
	"net"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"

	"openflux/tunnel/l3"
)

// The peer chooses where the traffic goes. An exit node must not become the
// way into the machine it runs on: 127.0.0.1, where the core opens a SOCKS5
// proxy with no authentication, and 169.254.169.254, where a cloud instance
// hands out its credentials. Private ranges stay reachable on purpose — sharing
// a LAN is a normal thing to want from an exit node.
var (
	egressMu   sync.Mutex
	egressAddr [4]byte
	egressAt   time.Time
)

// egressTTL bounds how stale the node's own address may get. The address
// changes when a DHCP lease renews, a VPN comes up or an interface flaps, and
// a value cached for the life of the process means the node's NEW address is
// never compared - reopening exactly the hole the comparison closes - while
// the old one stays blocked for no reason. The re-detect is one connected UDP
// socket that puts nothing on the wire, so running it every few minutes is
// free. An early call that finds no route caches zero only for one TTL
// instead of silently disabling the self-check for the life of the process.
const egressTTL = 5 * time.Minute

// egressIPv4 returns the node's own address, re-detected once the cached value
// is older than egressTTL.
func egressIPv4() [4]byte {
	egressMu.Lock()
	defer egressMu.Unlock()
	if time.Since(egressAt) < egressTTL {
		return egressAddr
	}
	egressAddr = detectEgressIPv4()
	egressAt = time.Now()
	return egressAddr
}

// exitDestinationAllowed reports whether an exit node may forward to addr.
func exitDestinationAllowed(addr tcpip.Address) bool {
	var dst [4]byte
	copy(dst[:], addr.AsSlice())
	return !l3.BlockedDestination(dst, egressIPv4())
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
