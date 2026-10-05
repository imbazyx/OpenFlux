package tunnel

import (
	"testing"
	"time"
)

// The duplicate SYN from the 2.3.0 capture: one packet out, two in, same seq.
func TestDropsTheSecondCopyOfOnePacket(t *testing.T) {
	d := newDedupe()
	syn := []byte("TCP 10.10.10.2:31735 -> 104.26.12.205:443 [SYN] seq=1936294202")

	if d.duplicate(syn, time.Now()) {
		t.Fatal("первый экземпляр не должен считаться дублем")
	}
	if !d.duplicate(syn, time.Now().Add(5*time.Millisecond)) {
		t.Fatal("второй экземпляр через 5мс обязан отбрасываться")
	}
	if got := d.droppedCount(); got != 1 {
		t.Fatalf("отброшено %d, ждали 1", got)
	}
}

// Distinct segments of a bulk transfer must all survive: same length, same
// window, no coincidence.
func TestDistinctSegmentsAreNotConfused(t *testing.T) {
	d := newDedupe()
	now := time.Now()
	for i := 0; i < dedupeCapacity; i++ {
		pkt := make([]byte, 1500)
		pkt[0] = byte(i)
		pkt[1499] = byte(i ^ 0x5a)
		if d.duplicate(pkt, now) {
			t.Fatalf("пакет %d отброшен как дубль", i)
		}
	}
	if got := d.droppedCount(); got != 0 {
		t.Fatalf("отброшено %d, ждали 0", got)
	}
}

// A TCP retransmission is byte-identical to the original. Dropping it is
// acceptable - the sender retransmits - but only inside the window; past it
// the copy is a fresh injection again.
func TestWindowExpires(t *testing.T) {
	d := newDedupe()
	pkt := []byte("segment")
	base := time.Now()

	if d.duplicate(pkt, base) {
		t.Fatal("первый экземпляр отброшен")
	}
	if !d.duplicate(pkt, base.Add(5*time.Millisecond)) {
		t.Fatal("дубль в окне не отброшен")
	}
	if d.duplicate(pkt, base.Add(dedupeWindow+time.Millisecond)) {
		t.Fatal("за пределами окна пакет не должен считаться дублем")
	}
}

// Ring wrap-around must not make an old packet "recent" again.
func TestRingWrapKeepsWindow(t *testing.T) {
	d := newDedupe()
	now := time.Now()
	pkt := []byte("wrapped")

	if d.duplicate(pkt, now) {
		t.Fatal("первый экземпляр отброшен")
	}
	for i := 0; i < dedupeCapacity*3; i++ {
		d.duplicate([]byte{byte(i)}, now.Add(time.Duration(i)*time.Millisecond))
	}
	// После обхода кольца исходная запись затирается, и пакет снова считается
	// новым: duplicate должен вернуть false.
	if d.duplicate(pkt, now.Add(10*time.Millisecond)) {
		t.Fatal("пакет вне окна принят за дубль после обхода кольца")
	}
}

// Same length but different content must not collide.
func TestDifferentContentSameLength(t *testing.T) {
	d := newDedupe()
	now := time.Now()
	if d.duplicate([]byte{1, 2, 3, 4}, now) {
		t.Fatal("первый отброшен")
	}
	if d.duplicate([]byte{1, 2, 3, 5}, now) {
		t.Fatal("разное содержимое принято за дубль")
	}
}
