package manager

import (
	"sync"
	"sync/atomic"
	"testing"
)

// The control handler runs each peer-requested TransportStart in its own
// goroutine (session.go's `go cb(sub, payload)`), so the transport cap is
// reached by many goroutines at once. This asserts the reservation holds: a
// burst of concurrent callers must win exactly maxTransports slots, no more.
//
// It failed before the reservation existed - every goroutine read Count() == 0
// and all of them proceeded, which is what let a peer attach several times
// over the cap.
func TestReserveSlotIsAtomicUnderConcurrency(t *testing.T) {
	const burst = 512

	m := &Manager{}
	var won atomic.Int64
	start := make(chan struct{})

	var done sync.WaitGroup
	done.Add(burst)
	for i := 0; i < burst; i++ {
		go func() {
			defer done.Done()
			<-start // released together, so they really do race
			if m.reserveSlot() == nil {
				won.Add(1)
			}
		}()
	}
	close(start)
	done.Wait()

	if got := won.Load(); got != maxTransports {
		t.Fatalf("concurrent reserveSlot: %d callers won a slot, want exactly %d\n"+
			"more than the cap means a peer can attach more transports than the limit allows",
			got, maxTransports)
	}
}

// A released slot must be reusable, and a slot must not be handed out past the
// cap once the cap is genuinely full.
func TestReserveSlotReleasesAndBlocks(t *testing.T) {
	m := &Manager{}

	for i := 0; i < maxTransports; i++ {
		if err := m.reserveSlot(); err != nil {
			t.Fatalf("slot %d: unexpected refusal: %v", i, err)
		}
	}
	if err := m.reserveSlot(); err == nil {
		t.Fatal("reserveSlot succeeded past the cap")
	}

	m.releaseSlot()
	if err := m.reserveSlot(); err != nil {
		t.Fatalf("a released slot was not reusable: %v", err)
	}
	if err := m.reserveSlot(); err == nil {
		t.Fatal("reserveSlot succeeded past the cap after a release")
	}

	// Entries that are already attached count against the cap too.
	m2 := &Manager{entries: map[string]*Entry{}}
	for i := 0; i < maxTransports; i++ {
		m2.entries[string(rune('a'+i))] = &Entry{}
	}
	if err := m2.reserveSlot(); err == nil {
		t.Fatal("reserveSlot ignored already-attached transports")
	}
}
