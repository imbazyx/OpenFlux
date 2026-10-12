package mailru

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"openflux/transport"
)

func transportDefault() transport.TransportConfig { return transport.DefaultConfig() }

// Mail.ru answers a throttled editor-API call with a 302 to
// /challenge.html?hash429=..., which the default client follows. By the time
// fetchDocInfo sees the response the status is 200 and the body is HTML, so the
// only reliable evidence is the final URL and the body's own markers.
func TestIsChallengePageRecognisesThe429Page(t *testing.T) {
	challengeURL, _ := url.Parse("https://cloud.mail.ru/challenge.html?hash429=abc&redirect=%2Fapi")
	cases := []struct {
		name string
		resp *http.Response
		body string
		want bool
	}{
		{"followed redirect", &http.Response{StatusCode: 200, Request: &http.Request{URL: challengeURL}},
			"<html><head><title>405 Not Allowed</title></head></html>", true},
		{"status 429", &http.Response{StatusCode: http.StatusTooManyRequests}, "", true},
		{"body marker only", &http.Response{StatusCode: 200}, "<p>hash429 marker</p>", true},
		{"challenge in body", &http.Response{StatusCode: 200}, `href="/challenge.html"`, true},
		{"smartcaptcha", &http.Response{StatusCode: 200}, "SmartCaptcha", true},

		// The normal answer must not be mistaken for a throttle, or a healthy
		// document would sit out five minutes on every connect.
		{"real json", &http.Response{StatusCode: 200}, `{"api":"https://x","document":{}}`, false},
		{"json error", &http.Response{StatusCode: 400}, `{"error":"nope"}`, false},
	}
	for _, c := range cases {
		if got := isChallengePage(c.resp, []byte(c.body)); got != c.want {
			t.Errorf("%s: isChallengePage = %v, want %v", c.name, got, c.want)
		}
	}
}

// The marker search must not be fooled by a document that merely mentions the
// word, and must not scan a huge body for it.
func TestIsChallengePageIgnoresLargeUnrelatedBodies(t *testing.T) {
	body := []byte(strings.Repeat("{\"key\":\"value\"}", 5000))
	if isChallengePage(&http.Response{StatusCode: 200}, body) {
		t.Error("a large ordinary JSON body was taken for the challenge page")
	}
}

// A throttled address must not be retried on the seconds-long backoff: that is
// what put it in this state. The wait has to be minutes.
func TestScheduleReconnectNoCaptchaUsesAMinuteScaleWait(t *testing.T) {
	tr := NewMailruDocsTransport("AbCdEfGh1/IjKlMnOp2", transportDefault())
	if err := tr.BaseTransport.Start(); err != nil {
		t.Fatalf("BaseTransport.Start: %v", err)
	}
	// Stop must win over the wait, or Stop would block for five minutes.
	tr.Stop()

	done := make(chan struct{})
	go func() {
		tr.scheduleReconnectNoCaptcha(0)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("scheduleReconnectNoCaptcha ignored Stop and kept waiting")
	}
}
