package tunnel

import (
	"time"
)

// The carrier underneath is not exactly-once.
//
// Mail.ru Docs relays a document update as a cursor event, and it does not
// promise to deliver it once. A 2.3.0 session captured on both ends at once
// shows it breaking the tunnel rather than merely wasting a copy:
//
//	client  -> [SYN] 10.10.10.2:31735 -> 104.26.12.205:443   (one packet,
//	         one batch: "sending batch of 1 packets")
//	exit    -> [SYN] 10.10.10.2:31735 -> 104.26.12.205:443   seq=1936294202
//	exit    -> [SYN] 10.10.10.2:31735 -> 104.26.12.205:443   seq=1936294202
//
// Identical sequence number, 5ms apart. Everything downstream is well
// behaved - ReadMessage dispatches once, decodeBatch splits once, the
// forwarder delivers once - so the second copy is injected as a genuinely
// fresh packet. A second SYN for a connection that is already established
// resets it, the client answers RST, and not one byte of payload ever
// crosses: the tunnel comes up, the transport is healthy, and the node
// refuses every new connection.
//
// Long-lived flows were unaffected, which is why this looked selective: the
// push connections in the same log (port 5228, 5222) had opened long before
// and carried 1400-byte segments fine. Only new connections died.
//
// Dropping the copy is the safe direction to err. TCP already treats a lost
// segment as normal and retransmits it on its own timer, so suppressing a
// duplicate costs a fraction of a retransmission timeout. Passing it costs
// the whole session.
const (
	dedupeWindow   = 250 * time.Millisecond
	dedupeCapacity = 64
)

type dedupeEntry struct {
	at    time.Time
	valid bool
	pkt   []byte
}

// dedupe is a fixed-size window of recently injected packets. Fixed size so
// bulk transfer cannot make it grow: at 64 entries the window spans well over
// the 5ms gap seen in the capture, and on a phone the whole thing costs about
// 96KB in the worst case.
type dedupe struct {
	entries [dedupeCapacity]dedupeEntry
	next    int
	dropped uint64
}

func newDedupe() *dedupe { return &dedupe{} }

// duplicate reports whether this exact packet was already injected inside the
// window. The bytes are kept rather than a hash: a collision would silently
// eat a real segment, and a copied slice is bounded by the packet size.
func (d *dedupe) duplicate(pkt []byte, now time.Time) bool {
	for i := range d.entries {
		e := &d.entries[i]
		if e.valid && now.Sub(e.at) < dedupeWindow && len(e.pkt) == len(pkt) && string(e.pkt) == string(pkt) {
			d.dropped++
			return true
		}
	}
	d.entries[d.next] = dedupeEntry{at: now, valid: true, pkt: append([]byte(nil), pkt...)}
	d.next = (d.next + 1) % dedupeCapacity
	return false
}

func (d *dedupe) droppedCount() uint64 { return d.dropped }
