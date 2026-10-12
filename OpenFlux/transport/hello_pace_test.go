package transport

import (
	"testing"
	"time"
)

// The hello pace is the only thing standing between an unattended node and a
// carrier ban: at helloInterval it writes four hellos a second forever into a
// shared document, which is the shape traffic that gets rate-limited. It must
// stay fast while a handshake looks imminent and slow down once nothing has
// answered for helloBackoff.
func TestHelloPaceDropsAfterBackoff(t *testing.T) {
	s := &Session{
		helloInterval: 250 * time.Millisecond,
		helloBackoff:  20 * time.Second,
		helloSlow:     2 * time.Second,
	}
	now := time.Now()

	if got := s.helloPace(now, time.Time{}); got != 250*time.Millisecond {
		t.Errorf("a session that just started must keep the fast pace, got %v", got)
	}
	if got := s.helloPace(now.Add(-25*time.Second), time.Time{}); got != 2*time.Second {
		t.Errorf("after the backoff window the pace must drop to helloSlow, got %v", got)
	}
	// A hello just went out, so the next tick must not send another.
	if got := s.helloPace(now, now.Add(-50*time.Millisecond)); got != 0 {
		t.Errorf("a hello sent 50 ms ago must suppress the next one, got %v", got)
	}
	if got := s.helloPace(now, now.Add(-3*time.Second)); got != 250*time.Millisecond {
		t.Errorf("after the pace elapsed another hello is due, got %v", got)
	}
	// helloInterval must never beat helloSlow into a fast loop: a session
	// configured slower than helloSlow keeps its own, slower pace.
	slow := &Session{helloInterval: 5 * time.Second, helloBackoff: time.Second, helloSlow: 2 * time.Second}
	if got := slow.helloPace(now.Add(-time.Hour), time.Time{}); got != 5*time.Second {
		t.Errorf("the configured slower pace must win over helloSlow, got %v", got)
	}
}
