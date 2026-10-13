package mailru

import (
	"sync"
	"testing"
	"time"
)

// Observed 2026-10-12 on a document refusing every session: the reader's own
// reconnect was under way at attempt 6 when the watchdog fired and dialled with
// attempt 0, discarding the run of short-lived sessions and the five-minute wait
// that went with it. A watchdog that has seen nothing must not be able to
// restart the ladder at the bottom.
//
// These drive advanceAttempt rather than scheduleReconnect: the latter really
// waits, and with a five-minute cooldown that is not a test.
func TestWatchdogReconnectContinuesTheLadder(t *testing.T) {
	tr := &MailruDocsTransport{}

	// Six refusals, as observed, each one short.
	for i := 0; i < 6; i++ {
		tr.noteSessionEnd(8 * time.Second)
		tr.advanceAttempt(tr.currentAttempt())
	}

	if got := tr.currentAttempt(); got != 6 {
		t.Fatalf("after six refusals the ladder is at %d, want 6", got)
	}
	if d := shortSessionCooldown(tr.shortSessions()); d == 0 {
		t.Fatal("six refusals in a row and the cooldown is still zero")
	}
}

// The immediate dial for a genuine blip must survive: a single dead socket has
// nothing to wait for, and delaying recovery by seconds is what the tunnel's
// own history says not to do.
func TestFirstBlipStillDialsImmediately(t *testing.T) {
	tr := &MailruDocsTransport{}
	tr.noteSessionEnd(2 * time.Second)

	if got := tr.currentAttempt(); got != 0 {
		t.Fatalf("before any scheduled reconnect the ladder is at %d, want 0", got)
	}
	if d := shortSessionCooldown(tr.shortSessions()); d != 0 {
		t.Fatalf("a single short session adds a %v wait, want none", d)
	}
}

// A session that lived clears the ladder the way it clears the cooldown: the
// service answered, so there is nothing to be patient about.
func TestHealthySessionClearsTheLadder(t *testing.T) {
	tr := &MailruDocsTransport{}
	for i := 0; i < 6; i++ {
		tr.noteSessionEnd(8 * time.Second)
		tr.advanceAttempt(tr.currentAttempt())
	}
	tr.noteSessionEnd(5 * time.Minute)

	// The reader resets by passing -1 once a session outlives 15 s.
	if got := tr.advanceAttempt(-1); got != 0 {
		t.Fatalf("after a session that lived the ladder restarts at %d, want 0", got)
	}
}

// Both reconnect entry points touch this; keep them safe together.
func TestLadderIsSafeUnderConcurrency(t *testing.T) {
	tr := &MailruDocsTransport{}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				tr.noteSessionEnd(time.Duration(j) * time.Millisecond)
				_ = tr.currentAttempt()
				tr.advanceAttempt(tr.currentAttempt())
			}
		}()
	}
	wg.Wait()
	if tr.currentAttempt() < 0 {
		t.Fatalf("the ladder went negative: %d", tr.currentAttempt())
	}
}
