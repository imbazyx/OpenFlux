// One-shot probe: does Mail.ru actually negotiate permessage-deflate, and does
// it include both *_no_context_takeover params that gorilla v1.5.3 requires?
//
// The EnableCompression flag in transport/mailru/mailru.go was added to stop a
// 409 on the WS upgrade. Two risks ride on it, and both are decided by what
// the server puts in its 101 response:
//   - gorilla rejects the handshake outright if the server answers
//     permessage-deflate without both no_context_takeover params.
//   - SetReadLimit counts compressed bytes, so a negotiated extension would
//     weaken the guard against a hostile participant in the public document.
//
// So print the raw response headers instead of guessing. Run from the repo
// root so the module resolves:
//
//	go run ./scripts/wsprobe/main.go <wss-url>
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

// userAgent must match mailruUserAgent in transport/mailru/mailru.go, or the
// probe stops reproducing the failure it is meant to explain.
const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

// oldUserAgent is the string that was in the core before the change, kept so
// the probe can separate the two edits: only one of them may be what turned
// the 409 into a 101.
const oldUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

func probe(label string, compress bool, ua string, wss string) {
	dialer := websocket.Dialer{
		EnableCompression: compress,
		HandshakeTimeout:  20 * time.Second,
	}
	headers := http.Header{}
	headers.Set("User-Agent", ua)
	headers.Set("Origin", "https://docs.datacloudmail.ru")

	conn, resp, err := dialer.Dial(wss, headers)
	if err != nil {
		code := 0
		if resp != nil {
			code = resp.StatusCode
		}
		fmt.Printf("%-22s ОШИБКА: %v (HTTP %d)\n", label, err, code)
		return
	}
	defer conn.Close()

	negotiated := resp.Header.Get("Sec-WebSocket-Extensions")
	fmt.Printf("%-22s HTTP %d, Sec-WebSocket-Extensions: %q\n", label, resp.StatusCode, negotiated)
	if negotiated == "" {
		fmt.Printf("%-22s -> сжатие НЕ согласовано, лимит чтения не ослаблен\n", label)
		return
	}
	both := contains(negotiated, "server_no_context_takeover") && contains(negotiated, "client_no_context_takeover")
	fmt.Printf("%-22s -> сжатие СОГЛАСОВАНО, оба *_no_context_takeover: %v\n", label, both)
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: wsprobe <document-url>")
		os.Exit(2)
	}
	wss, err := wsURLFor(os.Args[1])
	if err != nil {
		fmt.Println("fetchDocInfo:", err)
		os.Exit(1)
	}
	fmt.Println("WebSocket:", wss, "\n")
	// Old code sent the old UA and never offered compression. Walk both edits
	// independently so the fix is attributed to the one that actually changed
	// the response instead of to whichever landed first.
	probe("старый UA, без сжатия", false, oldUserAgent, wss)
	probe("старый UA, со сжатием", true, oldUserAgent, wss)
	probe("новый UA, без сжатия", false, userAgent, wss)
	probe("новый UA, со сжатием", true, userAgent, wss)
}

// wsURLFor does the same document lookup the core does, so the probe reaches
// the real URL instead of a hand-written guess.
func wsURLFor(docURL string) (string, error) {
	u, err := url.Parse(docURL)
	if err != nil {
		return "", err
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) != 3 || parts[0] != "public" {
		return "", fmt.Errorf("ожидался путь /public/<weblink>/<key>, получено %q", u.Path)
	}
	// normalizeWeblink in the core keeps the whole tail, key included.
	weblink := parts[1] + "/" + parts[2]

	// x-email is the literal "anonym" in the core, not the weblink.
	body := strings.NewReader(fmt.Sprintf(
		`{"x-email":"anonym","public":"/%s","platform":"desktop_web"}`, weblink))
	req, err := http.NewRequest(http.MethodPost, "https://cloud.mail.ru/api/v4/r7/edit", body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", weblink, weblink))

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d: %s", resp.StatusCode, raw)
	}

	var parsed struct {
		API      string `json:"api"`
		Document struct {
			Key string `json:"key"`
		} `json:"document"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", err
	}
	if parsed.Document.Key == "" {
		return "", fmt.Errorf("в ответе нет document.key: %s", raw)
	}
	api := strings.Replace(parsed.API, "https://", "wss://", 1)
	return fmt.Sprintf("%s/doc/%s/c/?EIO=4&transport=websocket", api, parsed.Document.Key), nil
}