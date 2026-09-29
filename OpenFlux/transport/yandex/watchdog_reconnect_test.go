package yandex

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

// The defect these cover: when the keep-alive write failed, the transport only
// cleared the connected flag. The socket stayed open, the reader stayed blocked
// in ReadMessage, nothing was scheduled, and the tunnel sat there looking
// connected to Android with no session behind it until someone toggled the VPN
// by hand. Observed on a phone over LTE, 2026-09-29.
//
// The fix routes every watchdog through requestReconnect, guarded so that a
// single network drop - which several goroutines notice at once - cannot dial a
// pile of sessions that displace and close each other. These check the two
// properties that keep it from becoming a new failure mode. No network is
// involved on purpose: a check that needs the internet is a check that quietly
// stops running.

// Exactly one watchdog may start the reconnect for a given drop.
func TestOnlyOneWatchdogWinsTheReconnect(t *testing.T) {
	var guard transport.ReconnectGuard

	const goroutines = 32
	var won int
	var mu sync.Mutex
	var wg sync.WaitGroup
	start := make(chan struct{})

	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start // release together, so the race is real
			if guard.TryClaim() {
				mu.Lock()
				won++
				mu.Unlock()
			}
		}()
	}
	close(start)
	wg.Wait()

	if won != 1 {
		t.Fatalf("%d watchdogs started a reconnect, want exactly 1: the losers "+
			"would dial sessions that close each other in a loop", won)
	}
}

// The guard must be releasable, or a failed attempt could never schedule the
// next one and the tunnel would stay down after the first failure.
func TestGuardIsReleasableAfterAFailedAttempt(t *testing.T) {
	var guard transport.ReconnectGuard

	if !guard.TryClaim() {
		t.Fatal("the first watchdog must win an unheld guard")
	}
	if guard.TryClaim() {
		t.Fatal("a held guard must refuse a second claim")
	}

	// What connectToDoc does on taking ownership.
	guard.Release()

	if !guard.TryClaim() {
		t.Fatal("a released guard must be claimable again, otherwise one " +
			"failed attempt strands the tunnel for good")
	}
}

// Past MaxReconnectAttempts the transport gives up. That path used to return in
// silence, which is what made the outage undiagnosable: nothing in the log
// separated "reconnecting" from "dead until you restart the app".
func TestGiveUpReturnsPromptlyAndDoesNotSpin(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.example/d", transport.DefaultConfig())
	// BaseTransport.Start, not Start(): the latter dials the real service, and
	// this test is about the give-up path, not about reaching Yandex. The
	// transport must count as running, or scheduleReconnect would return at the
	// IsRunning check and the test would pass without ever reaching the branch
	// it claims to cover.
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
		t.Fatal("a give-up must not hold the reconnect guard, or the next " +
			"failure could never schedule a fresh attempt")
	}
}

// The watchdog path must actually be wired. This drives the real keepAliveLoop
// against a socket whose peer is gone, with no reader goroutine anywhere: the
// exact state that used to strand the transport for good. Calling
// requestReconnect directly would pass even if the keep-alive loop stopped
// calling it, which is the wiring this is here to prove.
func TestKeepAliveLoopSchedulesAReconnectWithNoReader(t *testing.T) {
	// A real WebSocket whose server side is then closed, so writes fail the way
	// a dead peer looks rather than through a stub.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		up := websocket.Upgrader{}
		c, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		// Hold the connection open until the test tears the server down.
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
	// The peer is gone. Every write from here on fails.
	srv.CloseClientConnections()
	srv.Close()
	_ = conn.Close()

	// A short keep-alive interval so the loop fires now rather than in ten
	// seconds. It has to go through the constructor: GetConfig hands back a
	// copy, so setting the field on it changes nothing the loop will read.
	cfg := transport.DefaultConfig()
	cfg.KeepAliveInterval = 10 * time.Millisecond
	tr := NewYandexDocsTransport("https://docs.example/d", cfg)
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	defer tr.Stop()

	tr.Mu.Lock()
	tr.session = &DocSession{Conn: conn, UserID: "u1"}
	tr.SetConnected(true)
	tr.Mu.Unlock()

	go tr.keepAliveLoop()

	// The write may take a moment to fail; the first failure must claim the
	// reconnect. A pass that waits forever would be a check that never runs.
	deadline := time.Now().Add(3 * time.Second)
	for !tr.reconnecting.Held() {
		if time.Now().After(deadline) {
			t.Fatal("keepAliveLoop saw a dead socket and scheduled no " +
				"reconnect; without a reader goroutine this strands the tunnel")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// After Stop, a watchdog must not resurrect the tunnel.
func TestRequestReconnectAfterStopDoesNothing(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.example/d", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	tr.Stop()

	tr.requestReconnect("test: after stop")

	if tr.reconnecting.Held() {
		t.Fatal("a stopped transport must not schedule a reconnect")
	}
}
