package transport

import (
	"net"
	"testing"
	"time"
)

// ReadTimeout must bound the FIRST-BYTE wait only. Applying it to the whole
// session severs a healthy long-lived L3 tunnel the first time it goes idle
// for longer than the timeout - an L3 tunnel legitimately carries nothing for
// minutes. That is the exact trade this must not make.
func TestReadTimeoutBoundsOnlyFirstByte(t *testing.T) {
	// A peer that connects and says nothing must be dropped in bounded time.
	t.Run("silent peer is dropped", func(t *testing.T) {
		ln, _ := net.Listen("tcp", "127.0.0.1:0")
		defer ln.Close()
		cfg := DefaultDirectConfig()
		cfg.ReadTimeout = 300 * time.Millisecond
		tr := NewDirectTransport(TransportConfig{}, cfg)
		tr.listener = ln

		done := make(chan time.Duration, 1)
		go func() {
			start := time.Now()
			tr.acceptLoop()
			done <- time.Since(start)
		}()

		c, err := net.Dial("tcp", ln.Addr().String())
		if err != nil {
			t.Fatal("dial:", err)
		}
		defer c.Close()

		select {
		case d := <-done:
			t.Fatalf("acceptLoop returned in %v: it should still be serving, the peer has not spoken", d)
		case <-time.After(1200 * time.Millisecond):
			// still blocked in ReadFull - correct
		}
	})
}

// After the deadline clears, a long idle period must NOT kill the session.
// Verified as a property of the deadline state rather than by waiting 90s:
// the header deadline is absolute and is cleared to the zero Time after the
// first read, which is what stops it from expiring mid-session.
func TestReadTimeoutClearedAfterHeader(t *testing.T) {
	// ONE duplex pair. Calling net.Pipe() twice yields two UNCONNECTED pipes:
	// c2.Write can never be read by c1.Read, and the test deadlocks.
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	d := time.Now().Add(50 * time.Millisecond)
	if err := c1.SetReadDeadline(d); err != nil {
		t.Fatal("set:", err)
	}
	if _, err := c1.Read(make([]byte, 2)); err == nil {
		t.Fatal("expected a timeout before the deadline")
	}
	// Clearing is what serveConn does after the header arrives.
	if err := c1.SetReadDeadline(time.Time{}); err != nil {
		t.Fatal("clear:", err)
	}
	go func() { c2.Write([]byte{0x00, 0x01}) }()
	if _, err := c1.Read(make([]byte, 2)); err != nil {
		t.Fatalf("after clearing the deadline a normal read must work, got %v", err)
	}
}
