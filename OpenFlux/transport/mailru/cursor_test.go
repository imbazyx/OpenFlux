package mailru

import (
	"encoding/base64"
	"fmt"
	"reflect"
	"testing"

	"openflux/transport"
)

func cursorEntry(payload string) string {
	return fmt.Sprintf(`{"cursor":"18;%s","time":1790800029352,"user":"anonX","useridoriginal":"anonX"}`, payload)
}

func b64(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }

// The server batches cursor entries into one message under load. Every data
// entry must come out, in order, and keep-alives anywhere in the batch must not
// cost the data around them.
func TestCursorPayloadsBatch(t *testing.T) {
	ka := cursorEntry("---KA---")
	cases := []struct {
		name string
		text string
		want []string
	}{
		{"one entry", `42["message",{"type":"cursor","messages":[` + cursorEntry(b64("one")) + `]}]`, []string{b64("one")}},
		{"batched data", `42["message",{"type":"cursor","messages":[` + cursorEntry(b64("a")) + "," + cursorEntry(b64("b")) + "," + cursorEntry(b64("c")) + `]}]`, []string{b64("a"), b64("b"), b64("c")}},
		{"keep-alive first, data behind it", `42["message",{"type":"cursor","messages":[` + ka + "," + cursorEntry(b64("data")) + `]}]`, []string{b64("data")}},
		{"data, keep-alive, data", `42["message",{"type":"cursor","messages":[` + cursorEntry(b64("x")) + "," + ka + "," + cursorEntry(b64("y")) + `]}]`, []string{b64("x"), b64("y")}},
		{"only a keep-alive", `42["message",{"type":"cursor","messages":[` + ka + `]}]`, nil},
	}
	for _, c := range cases {
		if got := CursorPayloads(c.text); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

// The transport itself must deliver every packet of a batch, in order, with a
// keep-alive sitting in front of them. This is the failure that was measured:
// a frame containing "---KA---" was dropped whole, so every packet after the
// first in a batched frame was lost without a log line.
func TestHandleMessageDeliversEveryPacketOfABatch(t *testing.T) {
	tr := NewMailruDocsTransport("Vuri/test", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	msg := `42["message",{"type":"cursor","messages":[` + cursorEntry("---KA---") + "," +
		cursorEntry(b64("first")) + "," + cursorEntry(b64("second")) + `]}]`
	tr.handleMessage(&DocSession{}, []byte(msg))
	if !reflect.DeepEqual(got, []string{"first", "second"}) {
		t.Fatalf("delivered %v, want [first second]", got)
	}
}

// A frame that is nothing but a keep-alive must still be swallowed: it is
// liveness evidence, not data, and handing it to the codec would corrupt it.
// The second case exercises the frame-level guard at the top of handleMessage,
// which the batch tests do not reach because their frames contain "cursor".
func TestHandleMessageDropsLoneKeepAlive(t *testing.T) {
	tr := NewMailruDocsTransport("Vuri/test", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	tr.handleMessage(&DocSession{}, []byte(`42["message",{"type":"cursor","messages":[`+cursorEntry("---KA---")+`]}]`))
	if len(got) != 0 {
		t.Fatalf("a lone keep-alive reached the codec: %q", got)
	}
	tr.handleMessage(&DocSession{}, []byte(`42["message",{"keepAlive":"---KA---"}]`))
	if len(got) != 0 {
		t.Fatalf("a keep-alive frame without cursors reached the codec: %q", got)
	}
}

// One entry with broken base64 must not cost the entries behind it: the batch
// is decoded entry by entry, and an undecodable entry is skipped, not fatal.
func TestHandleMessageSkipsOnlyTheBrokenEntry(t *testing.T) {
	tr := NewMailruDocsTransport("Vuri/test", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	msg := `42["message",{"type":"cursor","messages":[` + cursorEntry(b64("first")) + "," +
		cursorEntry("!!!not base64!!!") + "," + cursorEntry(b64("second")) + `]}]`
	tr.handleMessage(&DocSession{}, []byte(msg))
	if !reflect.DeepEqual(got, []string{"first", "second"}) {
		t.Fatalf("delivered %v, want [first second]", got)
	}
}
