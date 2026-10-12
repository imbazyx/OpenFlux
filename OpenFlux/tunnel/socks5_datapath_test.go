package tunnel

import (
	"sync"
	"testing"
	"time"

	"openflux/transport"
)

// nullTransport satisfies everything the tunnel asks of a carrier and counts
// what it is handed. Embedding the interface keeps this from having to mirror
// BaseTransport, which carries locks.
type nullTransport struct {
	transport.Transport
	mu   sync.Mutex
	sent [][]byte
}

func (c *nullTransport) Send(data []byte) error {
	c.mu.Lock()
	c.sent = append(c.sent, append([]byte{}, data...))
	c.mu.Unlock()
	return nil
}

func (c *nullTransport) Receive(func([]byte)) {}

func (c *nullTransport) sentCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.sent)
}

// The legacy SOCKS5 fallback has to put a SYN on the carrier for a dial, or
// the SOCKS5 server accepts the connection and then simply times out. Observed
// on 2026-10-12 with packets=0 in [STATS] and a 10 s dial timeout on a carrier
// that was demonstrably healthy, which is indistinguishable from a broken data
// path until something checks this end on its own.
func TestDialTCPEmitsAPacketToTheCarrier(t *testing.T) {
	tr := &nullTransport{}
	tun := NewTCPTunnelMode(tr, false, 0)

	// The dial owns a 10 s internal timeout, so returning as soon as the
	// packet appears would leave a gVisor stack and its dial goroutine running
	// into the rest of the package's timing-sensitive tests - which showed up
	// as unrelated flakes in TestL4UDPDatagramRoundTrip. Close the stack and
	// wait for the dial to actually unwind before returning.
	dialed := make(chan struct{})
	go func() {
		defer close(dialed)
		if conn, err := tun.DialTCP("203.0.113.7:443"); err == nil {
			_ = conn.Close()
		}
	}()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && tr.sentCount() == 0 {
		time.Sleep(20 * time.Millisecond)
	}
	gotPacket := tr.sentCount() > 0

	tun.Close()
	select {
	case <-dialed:
	case <-time.After(5 * time.Second):
		t.Error("the dial did not unwind after the stack closed")
	}
	if !gotPacket {
		t.Fatal("five seconds of dialling produced no packet on the carrier; " +
			"the SOCKS5 fallback cannot carry traffic as it stands")
	}
}
