package l3

// A peer that can reach the transport chooses the destination itself. Without a
// filter the exit node is a proxy into whatever the machine running it can
// already reach — including 127.0.0.1, where the core starts a SOCKS5 proxy
// with no authentication, and 169.254.169.254, where a cloud instance hands
// out its credentials. That turns "my traffic goes out through the node" into
// "anyone who knows the node can attack the node".
//
// Private ranges are deliberately not blocked. Reaching a LAN through an exit
// node is an ordinary thing to want, and the operator's own LAN is usually the
// thing they want to share. What is refused is the host itself: loopback,
// link-local, the unspecified and multicast space, and its own address — the
// last one because an admin panel or a database often listens on the public
// address and nothing else.
//
// self is the node's own address, or the zero value when it could not be
// determined; the self-comparison is then skipped.
func BlockedDestination(dst [4]byte, self [4]byte) bool {
	switch {
	case dst == [4]byte{}:
		return true
	case self != [4]byte{} && dst == self:
		return true
	case dst[0] == 0: // "this network", never a routable destination
		return true
	case dst[0] == 127: // loopback
		return true
	case dst[0] == 169 && dst[1] == 254: // link-local, cloud metadata lives here
		return true
	case dst[0] >= 224: // multicast, broadcast, reserved
		return true
	}
	return false
}
