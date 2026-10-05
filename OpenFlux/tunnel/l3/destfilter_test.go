package l3

import "testing"

// The point of BlockedDestination is that an exit node cannot be used to reach
// the machine it runs on. These are the addresses that matter; a regression here
// turns a tunnel into an open proxy into the host.
func TestBlockedDestination(t *testing.T) {
	self := [4]byte{140, 235, 130, 236}

	blocked := []struct {
		name string
		dst  [4]byte
	}{
		{"loopback", [4]byte{127, 0, 0, 1}},
		{"loopback range", [4]byte{127, 13, 13, 13}},
		{"unspecified", [4]byte{0, 0, 0, 0}},
		{"this network", [4]byte{0, 1, 2, 3}},
		{"cloud metadata", [4]byte{169, 254, 169, 254}},
		{"link local", [4]byte{169, 254, 1, 1}},
		{"multicast", [4]byte{224, 0, 0, 1}},
		{"broadcast", [4]byte{255, 255, 255, 255}},
		{"the node's own address", [4]byte{140, 235, 130, 236}},
		{"zero address", [4]byte{}},
	}
	for _, tc := range blocked {
		if !BlockedDestination(tc.dst, self) {
			t.Errorf("%s: %v must be blocked on an exit node", tc.name, tc.dst)
		}
	}

	allowed := []struct {
		name string
		dst  [4]byte
	}{
		{"public", [4]byte{1, 1, 1, 1}},
		{"mail.ru", [4]byte{95, 163, 59, 187}},
		{"private, deliberately reachable", [4]byte{192, 168, 1, 10}},
		{"docker0", [4]byte{172, 17, 0, 1}},
		{"just below multicast", [4]byte{223, 255, 255, 255}},
		{"just above 169.254", [4]byte{169, 253, 255, 255}},
	}
	for _, tc := range allowed {
		if BlockedDestination(tc.dst, self) {
			t.Errorf("%s: %v must stay reachable", tc.name, tc.dst)
		}
	}
}

// A node whose own address could not be determined must still refuse loopback
// and link-local; only the self-comparison may be skipped.
func TestBlockedDestinationUnknownSelf(t *testing.T) {
	var unknown [4]byte
	for _, dst := range [][4]byte{{127, 0, 0, 1}, {169, 254, 169, 254}} {
		if !BlockedDestination(dst, unknown) {
			t.Errorf("%v must be blocked even without a known egress address", dst)
		}
	}
	if BlockedDestination([4]byte{1, 1, 1, 1}, unknown) {
		t.Error("a public address must stay reachable without a known egress address")
	}
}
