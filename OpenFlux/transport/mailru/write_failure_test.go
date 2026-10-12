package mailru

import (
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"openflux/transport"
)

// wrapSyscall gives the errno its own os.SyscallError wrapper, the shape the
// runtime actually returns from a write to a socket the peer dropped.
func wrapSyscall(err syscall.Errno) error {
	return &os.SyscallError{Syscall: "write", Err: err}
}

// deadConn returns a websocket the peer has already gone away from, so every
// write to it fails immediately.
func deadConn(t *testing.T) *websocket.Conn {
	t.Helper()
	up := make(chan struct{})
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		<-up
		_ = c.Close()
	}))
	conn, _, err := websocket.DefaultDialer.Dial(strings.Replace(srv.URL, "http", "ws", 1), nil)
	if err != nil {
		close(up)
		srv.Close()
		t.Fatalf("dial: %v", err)
	}
	// Order matters. The handler is parked on `up` and Server.Close waits for
	// outstanding handlers, so releasing it has to come first - the other way
	// round hangs the test until the package timeout.
	srv.CloseClientConnections()
	close(up)
	srv.Close()
	_ = conn.Close()
	return conn
}

// A failed write must drop the socket but must NOT schedule a reconnect.
//
// This is the storm: the writer holds the packet and retries every 15 ms, so a
// self-requested reconnect is re-armed on every one of those retries for the
// whole fetchDocInfo+dial window. Each pass resets the attempt counter, so
// MaxReconnectAttempts never trips and the carrier API takes a connect attempt
// every 15 ms - on the carrier that answers 429. Recovery belongs to the reader
// that owns the session, and to the keep-alive watchdog on its slow ticker.
func TestWriterFatalWriteDoesNotScheduleAReconnect(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transport.DefaultConfig())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	defer tr.Stop()

	tr.Mu.Lock()
	tr.session = &DocSession{
		Conn:       deadConn(t),
		UserID:     "u1",
		WriteQueue: make(chan []byte, 4),
	}
	tr.SetConnected(true)
	tr.Mu.Unlock()

	tr.session.WriteQueue <- []byte("payload")

	go tr.writerLoop()

	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if tr.Stats().Reconnects != 0 {
			t.Fatalf("the writer scheduled %d reconnects of its own; that is the "+
				"connect storm, one attempt per retry tick", tr.Stats().Reconnects)
		}
		if tr.reconnecting.Held() {
			t.Fatal("the writer claimed the reconnect guard; only the reader or " +
				"the keep-alive watchdog may do that")
		}
		time.Sleep(10 * time.Millisecond)
	}

	if tr.IsConnected() {
		t.Error("a fatal write left the transport marked connected")
	}
}

// ECONNRESET is what a NAT that dropped the socket sends back on the next
// write, and it is the commonest failure here. Read as transient, the writer
// hammered a dead socket forever and nothing ever scheduled a reconnect.
func TestFatalWriteErrorCatchesResetAndPipe(t *testing.T) {
	cases := map[string]error{
		"reset":       &net.OpError{Op: "write", Err: wrapSyscall(syscall.ECONNRESET)},
		"broken pipe": &net.OpError{Op: "write", Err: wrapSyscall(syscall.EPIPE)},
		"not conn":    &net.OpError{Op: "write", Err: wrapSyscall(syscall.ENOTCONN)},
		"aborted":     &net.OpError{Op: "write", Err: wrapSyscall(syscall.ECONNABORTED)},
		"closed":      net.ErrClosed,
		"nil":         nil,
	}
	for name, err := range cases {
		got := isFatalWriteError(err)
		want := err != nil
		if got != want {
			t.Errorf("%s: isFatalWriteError = %v, want %v", name, got, want)
		}
	}
}
