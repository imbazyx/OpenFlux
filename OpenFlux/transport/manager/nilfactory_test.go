package manager

import (
	"strings"
	"testing"

	"openflux/transport/control"
)

// The Android bridge builds its Manager with a nil Factory - a phone is handed
// its transports at startup and never builds one on demand. A peer that asks
// for a TransportStart anyway used to reach a call of a nil func value on a
// goroutine with no recover, which aborted the core: on a phone the peer is
// the exit node, so one packet killed the VPN.
//
// This asserts the call is refused as an error instead. It does not try to
// crash anything - a nil func call in a test would take the test binary with
// it, which is the very thing being guarded against.
func TestStartTransportWithoutFactoryIsRefused(t *testing.T) {
	m := New(nil, nil, "0123456789abcdef0123456789abcdef", "test")

	err := m.startTransport(&control.TransportConfig{
		Name: "peer-asked",
		Type: "direct",
	})
	if err == nil {
		t.Fatal("startTransport built a transport with no factory")
	}
	if !strings.Contains(err.Error(), "on demand") {
		t.Fatalf("wrong refusal %q - the caller logs this, so it has to say why", err)
	}
}

// The refusal must be specific to the missing factory, not a side effect of the
// other guards: a nil factory with an empty config is still an empty config.
func TestStartTransportChecksConfigBeforeFactory(t *testing.T) {
	m := New(nil, nil, "0123456789abcdef0123456789abcdef", "test")

	if err := m.startTransport(&control.TransportConfig{}); err == nil ||
		!strings.Contains(err.Error(), "empty config") {
		t.Fatalf("empty config with no factory: want the empty-config error, got %v", err)
	}
}
