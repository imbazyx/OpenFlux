package transport

import (
	"net"
	"sync"
	"testing"
	"time"
)

// The race this exists for: acceptLoop read t.listener unguarded while
// Stop() set it to nil under t.mu. No existing test stopped a DirectTransport
// while an accept was in flight, so -race never saw it.
//
// acceptLoop must be run on its own goroutine and Stop() from this one:
// acceptLoop serves each accepted connection INLINE, so calling it
// synchronously here would block forever waiting for a peer that never
// speaks - which is itself the behaviour audit item 4 is about.
func TestStopRacesAcceptLoop(t *testing.T) {
	for i := 0; i < 200; i++ {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Skip("no loopback listener:", err)
		}
		tr := NewDirectTransport(TransportConfig{}, DirectConfig{})

		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			tr.mu.Lock()
			tr.listener = ln
			tr.mu.Unlock()
			tr.acceptLoop()
		}()
		// Let the goroutine reach Accept(), then yank the listener from under
		// it. Repeat with a client connecting, so Accept is not idle.
		go func() {
			if c, err := net.Dial("tcp", ln.Addr().String()); err == nil {
				time.Sleep(time.Microsecond)
				c.Close()
			}
		}()
		time.Sleep(time.Microsecond)
		tr.Stop()

		done := make(chan struct{})
		go func() { wg.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatalf("iteration %d: acceptLoop did not return after Stop()", i)
		}
	}
}
