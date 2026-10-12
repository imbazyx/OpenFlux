package yandex

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

// Yandex batches cursor entries into one message under load, and a keep-alive
// can sit anywhere among them. Every data entry must come out, in order, and a
// keep-alive must not cost the data around it.
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

// The transport must deliver every packet of a batch, in order, with a
// keep-alive in front of them. The old code returned early on any frame
// containing "---KA---", so a batched frame lost everything behind it.
func TestHandleMessageDeliversEveryPacketOfABatch(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.yandex.ru/edit/d/abc", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	msg := `42["message",{"type":"cursor","messages":[` + cursorEntry("---KA---") + "," +
		cursorEntry(b64("first")) + "," + cursorEntry(b64("second")) + `]}]`
	tr.handleMessage(&DocSession{}, []byte(msg))
	if !reflect.DeepEqual(got, []string{"first", "second"}) {
		t.Fatalf("delivered %v, want [first second]", got)
	}
}

// A frame that is only a keep-alive is liveness evidence, not data: handing it
// to the codec would corrupt it.
func TestHandleMessageDropsLoneKeepAlive(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.yandex.ru/edit/d/abc", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	tr.handleMessage(&DocSession{}, []byte(`42["message",{"type":"cursor","cursor":"18;---KA---"}]`))
	if len(got) != 0 {
		t.Fatalf("a lone keep-alive reached the codec: %q", got)
	}
}

// One entry with broken base64 must not cost the entries behind it.
func TestHandleMessageSkipsOnlyTheBrokenEntry(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.yandex.ru/edit/d/abc", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	msg := `42["message",{"type":"cursor","messages":[` + cursorEntry(b64("first")) + "," +
		cursorEntry("!!!not base64!!!") + "," + cursorEntry(b64("second")) + `]}]`
	tr.handleMessage(&DocSession{}, []byte(msg))
	if !reflect.DeepEqual(got, []string{"first", "second"}) {
		t.Fatalf("delivered %v, want [first second]", got)
	}
}

// saveChanges carries a different payload and keeps its own extraction: the
// cursor regex does not match it, and it must still be delivered.
func TestHandleMessageDeliversSaveChanges(t *testing.T) {
	tr := NewYandexDocsTransport("https://docs.yandex.ru/edit/d/abc", transport.DefaultConfig())
	var got []string
	tr.Receive(func(b []byte) { got = append(got, string(b)) })
	payload := b64("savechanges")
	msg := `42["message",{"type":"saveChanges","excelAdditionalInfo":"` + payload + `"}]`
	tr.handleMessage(&DocSession{}, []byte(msg))
	if !reflect.DeepEqual(got, []string{"savechanges"}) {
		t.Fatalf("delivered %v, want [savechanges]", got)
	}
}
