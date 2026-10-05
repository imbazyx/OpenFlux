package tunnel

import (
	"net"
	"testing"

	"gvisor.dev/gvisor/pkg/tcpip"
)

// The l3 predicate has its own tests; this one exists because the L4 side had
// none, and because the l4 path is a different call site that a future edit
// could invert or remove while every other test stays green.
func TestExitDestinationAllowedRefusesTheHostItself(t *testing.T) {
	refuse := func(ip string) {
		t.Helper()
		v4 := net.ParseIP(ip).To4()
		if v4 == nil {
			t.Fatalf("%s is not IPv4", ip)
		}
		addr := tcpip.AddrFrom4([4]byte{v4[0], v4[1], v4[2], v4[3]})
		if exitDestinationAllowed(addr) {
			t.Errorf("exit node must refuse %s", ip)
		}
	}
	refuse("127.0.0.1")
	refuse("127.53.1.9")
	refuse("169.254.169.254")
	refuse("0.0.0.0")
	refuse("224.0.0.1")

	allow := tcpip.AddrFrom4([4]byte{1, 1, 1, 1})
	if !exitDestinationAllowed(allow) {
		t.Error("a public address must stay reachable through an exit node")
	}
	// The node's own address is refused only when it can be detected, which
	// depends on the host's routing table. Assert the self-check is live here
	// rather than silently reporting success on a zero address.
	if got := egressIPv4(); got != [4]byte{} {
		self := tcpip.AddrFrom4(got)
		if exitDestinationAllowed(self) {
			t.Errorf("exit node must refuse its own address %s", self)
		}
	}
}
