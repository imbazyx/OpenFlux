package transport

import "sync/atomic"

// ReconnectGuard admits exactly one reconnect at a time.
//
// A single network drop is noticed by several goroutines at once: the reader
// blocked in ReadMessage, the keep-alive writer whose send just failed, and the
// health watchdog that noticed the channel went quiet. Each of them is right
// that the session is dead. Left unguarded they would each dial a fresh
// session, and each new session displaces and closes the previous one - a
// self-sustaining loop that burns quota on a public service and, because every
// pass reset the attempt counter, never reaches the limit that would stop it.
//
// It must be releasable. The attempt that wins claims the guard, then releases
// it as soon as it starts running: a FAILED attempt has to be able to schedule
// the next one, or the first failure strands the tunnel permanently.
type ReconnectGuard struct {
	held atomic.Bool
}

// TryClaim reports whether the caller now owns the reconnect. Exactly one
// caller gets true; everyone else gets false and does nothing, because a
// reconnect is already under way and will pick up the dead session on its own.
func (g *ReconnectGuard) TryClaim() bool {
	return g.held.CompareAndSwap(false, true)
}

// Release hands ownership back, so a failed attempt can schedule a follow-up.
func (g *ReconnectGuard) Release() {
	g.held.Store(false)
}

// Held reports whether a reconnect is currently in flight.
func (g *ReconnectGuard) Held() bool {
	return g.held.Load()
}
