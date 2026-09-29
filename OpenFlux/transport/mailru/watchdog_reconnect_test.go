package mailru

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"openflux/transport"
)

// The defect these cover, observed on a phone over Beeline LTE on 2026-09-29:
//
//	[M-DOCS] Keep-alive failed: use of closed network connection
//	[BATCH] send error (total=79): transport not connected
//
// and then nothing. closeSession cleared the flag and closed the socket, but
// recovery was left to whichever goroutine was blocked in ReadMessage. When
// none was, the tunnel stayed down with the TUN interface still up, so Android
// kept reporting "connected" and Chrome kept hanging on a loading bar until
// the user toggled the VPN by hand. On top of that, exhausting
// MaxReconnectAttempts returned in silence, so the log could not tell
// "reconnecting" apart from "dead forever".
//
// Mail.ru is the transport this failure was seen on, and it had no tests at
// all, so the fix here would otherwise have shipped unverified.

// Exactly one watchdog may start the reconnect for a single network drop.
func TestOnlyOneWatchdogWinsTheReconnect(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transport.DefaultConfig())
	if tr == nil {
		t.Fatal("NewMailruDocsTransport returned nil")
	}
	if tr.reconnecting.TryClaim() != true {
		t.Fatal("the first watchdog must win an unheld guard")
	}
	if tr.reconnecting.TryClaim() {
		t.Fatal("a held guard must refuse a second claim: two reconnects would " +
			"dial sessions that displace and close each other")
	}
	tr.reconnecting.Release()
	if !tr.reconnecting.TryClaim() {
		t.Fatal("a released guard must be claimable again, otherwise one " +
			"failed attempt strands the tunnel for good")
	}
}

// The wiring that matters: drive the real keepAliveLoop against a socket whose
// peer is gone, with no reader goroutine, and require that it schedules a
// reconnect by itself. Calling requestReconnect directly would pass even if the
// loop stopped calling it.
func TestKeepAliveLoopSchedulesAReconnectWithNoReader(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		up := websocket.Upgrader{}
		c, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		done := make(chan struct{})
		<-done
		_ = c.Close()
		close(done)
	}))
	defer srv.Close()

	conn, _, err := websocket.DefaultDialer.Dial(strings.Replace(srv.URL, "http", "ws", 1), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	// The peer is gone; every write from here on fails.
	srv.CloseClientConnections()
	srv.Close()
	_ = conn.Close()

	// Through the constructor: GetConfig hands back a copy, so setting the
	// interval on it would not change what the loop reads.
	cfg := transport.DefaultConfig()
	cfg.KeepAliveInterval = 10 * time.Millisecond
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", cfg)
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	defer tr.Stop()

	tr.Mu.Lock()
	tr.session = &DocSession{Conn: conn, UserID: "u1"}
	tr.SetConnected(true)
	tr.Mu.Unlock()

	go tr.keepAliveLoop()

	deadline := time.Now().Add(3 * time.Second)
	for !tr.reconnecting.Held() {
		if time.Now().After(deadline) {
			t.Fatal("keepAliveLoop saw a dead socket and scheduled no reconnect; " +
				"with no reader goroutine this leaves the tunnel dark forever")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// Exhausting the attempts must return promptly and not hold the guard, or the
// next failure could never schedule a fresh attempt.
func TestGiveUpReturnsPromptlyAndDoesNotSpin(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	defer tr.Stop()

	attempts := tr.GetConfig().MaxReconnectAttempts + 1

	done := make(chan struct{})
	go func() {
		tr.scheduleReconnect(attempts)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("scheduleReconnect(%d) did not return promptly; it must give "+
			"up rather than loop", attempts)
	}
	if tr.reconnecting.Held() {
		t.Fatal("a give-up must not hold the reconnect guard")
	}
}

// Concurrent watchdogs must collapse into a single reconnect.
func TestConcurrentWatchdogsCollapseToOne(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	defer tr.Stop()

	const goroutines = 32
	var won int
	var mu sync.Mutex
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if tr.reconnecting.TryClaim() {
				mu.Lock()
				won++
				mu.Unlock()
			}
		}()
	}
	close(start)
	wg.Wait()

	if won != 1 {
		t.Fatalf("%d watchdogs started a reconnect, want exactly 1", won)
	}
}

// After Stop, a watchdog must not resurrect the tunnel.
func TestRequestReconnectAfterStopDoesNothing(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	tr.Stop()

	tr.requestReconnect("test: after stop")

	if tr.reconnecting.Held() {
		t.Fatal("a stopped transport must not schedule a reconnect")
	}
}
