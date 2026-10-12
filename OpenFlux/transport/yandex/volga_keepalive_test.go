package yandex

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"testing"
)

// buildBundle wraps payloads the way the relay does: each length-prefixed,
// the whole thing base64-encoded inside a bundle item.
func buildBundle(t *testing.T, payloads ...[]byte) json.RawMessage {
	t.Helper()
	var blob []byte
	for _, p := range payloads {
		var l [2]byte
		binary.BigEndian.PutUint16(l[:], uint16(len(p)))
		blob = append(blob, l[:]...)
		blob = append(blob, p...)
	}
	encoded, err := json.Marshal(base64.StdEncoding.EncodeToString(blob))
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return encoded
}

// The relay keep-alive is a single zero byte. It is not a packet, and the
// sender already knows that - it excludes the same shape from its data
// counter. Handing it to the data path anyway produced one
// "batch frame too short: 1 bytes" from the batched codec every interval,
// per peer, on a transport that otherwise carries traffic fine.
func TestHandleBundleItemSkipsTheKeepAlive(t *testing.T) {
	w := &wsListener{stats: &VolgaStats{}}
	var got [][]byte
	w.onData = func(b []byte) { got = append(got, append([]byte{}, b...)) }

	w.handleBundleItem(buildBundle(t, []byte{0}, []byte("payload")))

	if len(got) != 1 || string(got[0]) != "payload" {
		t.Fatalf("data path saw %v, want only the real packet", got)
	}
	if n := w.stats.DataPacketsRecv.Load(); n != 1 {
		t.Fatalf("DataPacketsRecv = %d, want 1", n)
	}
}

// A bundle that is nothing but keep-alives must deliver nothing at all.
func TestHandleBundleItemOfOnlyKeepAlivesDeliversNothing(t *testing.T) {
	w := &wsListener{stats: &VolgaStats{}}
	delivered := 0
	w.onData = func([]byte) { delivered++ }

	w.handleBundleItem(buildBundle(t, []byte{0}, []byte{0}))

	if delivered != 0 {
		t.Fatalf("the data path received %d keep-alives", delivered)
	}
}

// Filtering the one-byte zero must not filter a real packet that happens to be
// short. The encrypted envelope is far larger, but the filter must still be
// about the keep-alive and nothing else.
func TestHandleBundleItemKeepsOtherShortPackets(t *testing.T) {
	w := &wsListener{stats: &VolgaStats{}}
	var got [][]byte
	w.onData = func(b []byte) { got = append(got, append([]byte{}, b...)) }

	w.handleBundleItem(buildBundle(t, []byte{0x01}, []byte("ab")))

	if len(got) != 2 || string(got[0]) != "\x01" || string(got[1]) != "ab" {
		t.Fatalf("data path saw %q, want both packets", got)
	}
}
