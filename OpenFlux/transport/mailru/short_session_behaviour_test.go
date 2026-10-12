package mailru

import (
	"sync"
	"testing"
	"time"
)

// maxReconnectBackoff is reconnectBackoff's ceiling: 15 s plus its 50% jitter.
// Anything the cooldown promises has to clear it on every draw, not most.
const maxReconnectBackoff = 22500 * time.Millisecond

// The bug this guards: with the plain exponential backoff, sessions that never
// outlive ten seconds still get re-dialled about every twenty seconds, forever.
// Measured on 2026-10-12 against a live Mail.ru document over hours.
func TestShortLivedSessionsPushTheReconnectPastTheBackoffCap(t *testing.T) {
	tr := &MailruDocsTransport{}

	// A single blip stays the ordinary backoff's business: the delay the cooldown
	// adds on top must still be zero, which is the only way to say this without
	// comparing two independent draws of the jitter.
	if d := tr.effectiveWait(20); d > maxReconnectBackoff {
		t.Fatalf("no short sessions yet -> %v, past the ordinary backoff ceiling %v",
			d, maxReconnectBackoff)
	}

	tr.noteSessionEnd(8 * time.Second)
	if c := shortSessionCooldown(tr.shortSessions()); c != 0 {
		t.Fatalf("one short session adds a %v delay, want none yet", c)
	}
	if d := tr.effectiveWait(20); d > maxReconnectBackoff {
		t.Fatalf("one short session -> %v, past the ordinary backoff ceiling %v",
			d, maxReconnectBackoff)
	}

	tr.noteSessionEnd(9 * time.Second)
	two := tr.effectiveWait(20)
	tr.noteSessionEnd(3 * time.Second)
	three := tr.effectiveWait(21)

	if two <= maxReconnectBackoff {
		t.Fatalf("two short-lived sessions -> %v, want past the %v backoff ceiling",
			two, maxReconnectBackoff)
	}
	if three <= two {
		t.Fatalf("the wait did not escalate: %v then %v", two, three)
	}
	if three > rateLimitReconnectWait {
		t.Fatalf("wait %v exceeds the cap %v", three, rateLimitReconnectWait)
	}
}

// A session that lived is evidence the service is answering. The count has to
// go back to zero or one good session in twenty would inherit an hour of
// patience from the twenty that failed before it.
func TestHealthySessionResetsTheCount(t *testing.T) {
	tr := &MailruDocsTransport{}
	for i := 0; i < 6; i++ {
		tr.noteSessionEnd(5 * time.Second)
	}
	if d := tr.effectiveWait(20); d <= maxReconnectBackoff {
		t.Fatalf("six refusals -> %v, want past the ordinary ceiling %v",
			d, maxReconnectBackoff)
	}
	tr.noteSessionEnd(2 * time.Minute)

	if n := tr.shortSessions(); n != 0 {
		t.Fatalf("after a session that lived, the count is %d, want 0", n)
	}
	if d := tr.effectiveWait(20); d > maxReconnectBackoff {
		t.Fatalf("after a healthy session the wait is %v, want the plain backoff back", d)
	}
}

// Concurrent readers and the writer goroutine touch this counter; this runs
// under the race detector on its own, so keep it honest.
func TestShortSessionCountIsSafeUnderConcurrency(t *testing.T) {
	tr := &MailruDocsTransport{}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				if i%2 == 0 {
					tr.noteSessionEnd(2 * time.Second)
				} else {
					tr.noteSessionEnd(time.Minute)
				}
				_ = tr.effectiveWait(j)
			}
		}(i)
	}
	wg.Wait()
}
