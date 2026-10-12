package mailru

import (
	"testing"
	"time"
)

// A single blip is the ordinary backoff's business. Sessions that keep coming
// back short are not a blip: measured on 2026-10-12 Mail.ru never let one past
// 9.8 s, and the node re-dialled about every twenty seconds for hours until
// the address started refusing connections outright.
func TestShortSessionCooldownStaysOutOfTheWayForOneBlip(t *testing.T) {
	for _, n := range []int32{0, 1} {
		if d := shortSessionCooldown(n); d != 0 {
			t.Fatalf("%d short sessions -> %v, want no extra wait", n, d)
		}
	}
}

func TestShortSessionCooldownGrowsAndThenStopsGrowing(t *testing.T) {
	// The floor has to clear the ordinary backoff's ceiling, which is 15 s
	// plus its 50% jitter. Anything lower gets swallowed by the jitter on some
	// draws, so the escalation appears to work only by luck.
	if d := shortSessionCooldown(2); d <= 22*time.Second {
		t.Fatalf("two short sessions -> %v, want more than the 22.5s backoff ceiling", d)
	}
	if d := shortSessionCooldown(3); d != 60*time.Second {
		t.Fatalf("three -> %v, want 60s", d)
	}

	prev := time.Duration(0)
	for n := int32(4); n <= 40; n++ {
		d := shortSessionCooldown(n)
		if d < prev {
			t.Fatalf("%d short sessions -> %v, shorter than %v before it", n, d, prev)
		}
		if d > rateLimitReconnectWait {
			t.Fatalf("%d short sessions -> %v, past the cap", n, d)
		}
		prev = d
	}
	if prev != rateLimitReconnectWait {
		t.Fatalf("a long run of refusals settles at %v, want the %v cap", prev, rateLimitReconnectWait)
	}
}

// The cap has to be the one the rate-limit wait already uses, or a throttled
// document gets dialed twice as often by two different rules.
func TestShortSessionCooldownMatchesTheRateLimitWait(t *testing.T) {
	if got := shortSessionCooldown(100); got != rateLimitReconnectWait {
		t.Fatalf("cooldown %v, rate-limit wait %v", got, rateLimitReconnectWait)
	}
}
