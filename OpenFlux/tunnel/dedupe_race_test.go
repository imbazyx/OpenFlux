package tunnel

import (
	"encoding/binary"
	"sync"
	"testing"
	"time"
)

// dedupe is shared by every carrier's reader goroutine, because a Session
// installs one receive callback per link and each link has its own goroutine.
// A profile the app really imports carries two carriers (vyandex + direct), so
// two writers really do call duplicate() at once.
//
// The entry write is not atomic: it is three words, and a reader can take the
// pointer from the new packet and the length from the old one, then read past
// the end of the shorter allocation. That is a panic in a carrier's reader,
// with no recover on that path, so it takes the process with it - on a node,
// every client on it.
//
// This test is what pins the mutex in place. Remove it and `go test -race`
// reports the race again.
func TestDedupeConcurrent(t *testing.T) {
	d := newDedupe()
	now := time.Now()

	const writers = 4
	const perWriter = 400
	var wg sync.WaitGroup
	wg.Add(writers)
	for w := 0; w < writers; w++ {
		go func(w int) {
			defer wg.Done()
			for i := 0; i < perWriter; i++ {
				// Same instant for every writer, so the window never slides and
				// the entries genuinely contend rather than quietly expiring.
				pkt := make([]byte, 64)
				binary.BigEndian.PutUint32(pkt, uint32(w))
				binary.BigEndian.PutUint32(pkt[4:], uint32(i))
				d.duplicate(pkt, now)
			}
		}(w)
	}
	wg.Wait()

	// Whatever the interleaving, the bookkeeping must still be coherent: the
	// ring never runs past the window and the count never exceeds the calls.
	if got, dropped := d.droppedCount(), uint64(writers*perWriter); got > dropped {
		t.Fatalf("dropped %d of %d calls: impossible", got, dropped)
	}
}

// A packet must be recognised as its own duplicate regardless of who arrives
// first, which is the property the guard exists for.
func TestDedupeSeesItsOwnDuplicate(t *testing.T) {
	d := newDedupe()
	now := time.Now()
	pkt := []byte("the same segment twice")
	if d.duplicate(pkt, now) {
		t.Fatal("the first sighting was called a duplicate")
	}
	if !d.duplicate(pkt, now) {
		t.Fatal("the second sighting was not recognised")
	}
	// And it must expire: otherwise a retransmission would be eaten forever.
	if d.duplicate(pkt, now.Add(dedupeWindow+time.Millisecond)) {
		t.Fatal("the entry never expired")
	}
}
