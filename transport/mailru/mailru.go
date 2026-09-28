// Package mailru implements a transport that tunnels packets through
// Mail.ru's cloud document editor (docs.datacloudmail.ru), the same
// coauthoring backend family as Yandex.Docs. Two peers open the same
// public document and smuggle packets through the "cursor" field of the
// collaborative editing protocol.
package mailru

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"openflux/netbind"
	"openflux/transport"
	"openflux/utils"
)

const mailruUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

var cursorPayloadRe = regexp.MustCompile(`"cursor":"[^;]+;([^"]+)"`)

type MailruDocsInfo struct {
	Token        string
	DocKey       string
	WsURL        string
	FileType     string
	DocURL       string
	DocTitle     string
	Permissions  map[string]interface{}
	CallbackURL  string
	EditorUserID string
}

type DocSession struct {
	Info       MailruDocsInfo
	Conn       *websocket.Conn
	WriteQueue chan []byte
	UserID     string
	writeMu    sync.Mutex
}

func (s *DocSession) safeWrite(messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.Conn.WriteMessage(messageType, data)
}

type MailruDocsTransport struct {
	*transport.BaseTransport

	weblink string
	session *DocSession

	userCounter atomic.Int32
	baseUserID  string

	cookieJar *cookiejar.Jar
	jarMu     sync.RWMutex

	// lastRx is the wall-clock (unix nanos) of the last inbound frame of ANY
	// kind (server Socket.IO ping "2", peer cursors, auth). rxIdleLoop uses
	// it to detect a half-dead socket: more than rxIdleLimit of silence while
	// "connected" means the channel is broken even though writes succeed.
	lastRx atomic.Int64

	// Tunnel-level (not frame-level) liveness. A client whose socket died for
	// reads only still writes: its packets reach us, our replies never get
	// ACKed, and both sides look "connected" forever. lastTxData/lastRxData
	// expose exactly that asymmetry (verified 2026-09-27 on a phone: its SYNs
	// arrived, our SYN-ACKs were never ACKed and retrans climbed).
	lastTxData   atomic.Int64
	lastRxData   atomic.Int64
	peerAlive    atomic.Int64
	sessionStart atomic.Int64
	oneWaySince  atomic.Int64
}

// NewMailruDocsTransport accepts either a bare weblink ("AbCdEfGh1/IjKlMnOp2")
// or a full public URL ("https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2"),
// normalizing the latter to the former.
func NewMailruDocsTransport(weblink string, config transport.TransportConfig) *MailruDocsTransport {
	t := &MailruDocsTransport{
		BaseTransport: transport.NewBaseTransport(config),
		weblink:       normalizeWeblink(weblink),
	}
	t.baseUserID = randUserID()
	jar, _ := cookiejar.New(nil)
	t.cookieJar = jar
	return t
}

func normalizeWeblink(weblink string) string {
	weblink = strings.TrimSpace(weblink)
	for _, prefix := range []string{
		"https://cloud.mail.ru/public/",
		"http://cloud.mail.ru/public/",
		"https://cloud.mail.ru/",
		"http://cloud.mail.ru/",
	} {
		if strings.HasPrefix(weblink, prefix) {
			return strings.Trim(strings.TrimPrefix(weblink, prefix), "/")
		}
	}
	return weblink
}

func (t *MailruDocsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	t.baseUserID = randUserID()
	utils.SafeGo("mailru.keepAlive", t.keepAliveLoop)
	utils.SafeGo("mailru.rxIdle", t.rxIdleLoop)
	utils.SafeGo("mailru.docKey", t.docKeyLoop)
	utils.SafeGo("mailru.health", t.healthLoop)
	t.connectToDoc(0)

	return nil
}

func (t *MailruDocsTransport) Send(data []byte) error {
	if !t.IsConnected() {
		return fmt.Errorf("transport not connected")
	}

	t.Mu.RLock()
	session := t.session
	t.Mu.RUnlock()

	if session == nil {
		return fmt.Errorf("no active session")
	}

	select {
	case session.WriteQueue <- data:
		t.RecordSend(len(data))
		t.lastTxData.Store(time.Now().UnixNano())
		return nil
	default:
		return fmt.Errorf("write queue full")
	}
}

func (t *MailruDocsTransport) connectToDoc(attempt int) {
	if !t.IsRunning() {
		return
	}

	utils.Debugf("[M-DOCS] connectToDoc attempt %d", attempt)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				utils.Debugf("[PANIC] recovered in mailru.connect: %v", r)
			}
		}()
		t.Mu.Lock()
		existingSession := t.session
		t.Mu.Unlock()

		var userID string
		if existingSession != nil {
			userID = existingSession.UserID
		} else {
			suffix := fmt.Sprintf("%03d", t.userCounter.Add(1)%1000)
			userID = t.baseUserID + suffix
		}

		info, err := t.fetchDocInfo(t.weblink)
		if err != nil {
			utils.Debugf("[M-DOCS] fetchDocInfo failed: %v", err)
			t.scheduleReconnect(attempt)
			return
		}

		dialer := websocket.Dialer{
			HandshakeTimeout: 15 * time.Second,
			NetDialContext: netbind.Wrap(&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		}
		headers := http.Header{}
		headers.Set("User-Agent", mailruUserAgent)
		headers.Set("Origin", "https://docs.datacloudmail.ru")

		utils.Debugf("[M-DOCS] WebSocket dial %s", info.WsURL)
		conn, resp, err := dialer.Dial(info.WsURL, headers)
		if err != nil {
			status := 0
			if resp != nil {
				status = resp.StatusCode
			}
			utils.Debugf("[M-DOCS] WebSocket dial failed (http %d): %v", status, err)
			t.scheduleReconnect(attempt)
			return
		}
		utils.Debugf("[M-DOCS] WebSocket connected")

		writeQueue := make(chan []byte, t.GetConfig().MaxQueueSize)
		if existingSession != nil {
			writeQueue = existingSession.WriteQueue
		}

		session := &DocSession{
			Info:       info,
			Conn:       conn,
			WriteQueue: writeQueue,
			UserID:     userID,
		}

		t.Mu.Lock()
		t.session = session
		t.SetConnected(true)
		now := time.Now()
		t.lastRx.Store(now.UnixNano()) // grace period for the first frame
		t.sessionStart.Store(now.UnixNano())
		// The one-way detector judges THIS session's peer, so its liveness
		// clocks start here. Left over from the previous session they would let
		// a brand new tunnel be judged by timestamps from the old one.
		t.lastTxData.Store(0)
		t.lastRxData.Store(0)
		t.peerAlive.Store(0)
		t.oneWaySince.Store(0)
		t.Mu.Unlock()

		if existingSession == nil {
			utils.SafeGo("mailru.writer", t.writerLoop)
		} else if existingSession.Conn != nil {
			// We reused its WriteQueue, so this is the same logical session
			// and its previous socket is now displaced. Nothing else closes it:
			// its reader stays parked in ReadMessage holding a participant slot on
			// the public link, and keeps calling CallReceive on a stale socket.
			_ = existingSession.Conn.Close()
		}

		// Auth - fired immediately, same as the Yandex.Docs transport. No
		// need to wait for the server's own "0{"/"40" handshake frames
		// first: Mail.ru's coauthoring server buffers and processes these
		// once its own session state catches up, and waiting for explicit
		// acks here only stretches the outage window on every reconnect
		// (Mail.ru can delay a fresh joiner's auth confirmation by up to
		// ~30s while it reconciles with the other participant).
		auth1 := fmt.Sprintf(`40{"token":"%s"}`, info.Token)
		session.safeWrite(websocket.TextMessage, []byte(auth1))

		authMsg := map[string]interface{}{
			"type":                "auth",
			"docid":               info.DocKey,
			"documentCallbackUrl": info.CallbackURL,
			"token":               "fghhfgsjdgfjs",
			"user": map[string]interface{}{
				"id":        info.EditorUserID,
				"username":  userID,
				"indexUser": -1,
			},
			"editorType":         0,
			"lastOtherSaveTime":  -1,
			"block":              []interface{}{},
			"documentFormatSave": 65,
			"view":               false,
			"isCloseCoAuthoring": false,
			"openCmd": map[string]interface{}{
				"c":               "open",
				"id":              info.DocKey,
				"userid":          info.EditorUserID,
				"format":          info.FileType,
				"url":             info.DocURL,
				"title":           info.DocTitle,
				"lcid":            25,
				"nobase64":        true,
				"convertToOrigin": ".pdf.xps.oxps.djvu",
			},
			"lang":                  "ru",
			"mode":                  "edit",
			"permissions":           info.Permissions,
			"IsAnonymousUser":       false,
			"timezoneOffset":        -180,
			"coEditingMode":         "fast",
			"jwtOpen":               info.Token,
			"time":                  1000,
			"supportAuthChangesAck": true,
		}
		messagePart, _ := json.Marshal([]interface{}{"message", authMsg})
		session.safeWrite(websocket.TextMessage, []byte(fmt.Sprintf("42%s", string(messagePart))))

		connectedAt := time.Now()
		for t.IsRunning() {
			_, message, err := conn.ReadMessage()
			if err != nil {
				utils.Debugf("[M-DOCS] Read error: %v", err)
				// closeSession answers "was this the live session?". It MUST be
				// consulted, not just called: connectToDoc closes the conn of the
				// session it displaces, which wakes that session's own reader
				// and lands it right here. Scheduling a reconnect from a reader
				// whose session was already replaced starts a self-sustaining
				// loop - install B, close A, reader A reconnects, installs C,
				// closes B... - and because each pass resets `attempt` to 0/-1,
				// MaxReconnectAttempts never trips, so the transport can never
				// stay up. Only the reader that owned the live session has the
				// right to reconnect on its behalf.
				if !t.closeSession(session) {
					utils.Debugf("[M-DOCS] reader of a displaced session stopped, not reconnecting")
					conn.Close()
					return
				}
				conn.Close()

				next := attempt
				if time.Since(connectedAt) > 15*time.Second {
					next = -1
				}
				t.scheduleReconnect(next)
				return
			}
			t.handleMessage(session, message)
		}
	}()
}

func (t *MailruDocsTransport) writerLoop() {
	// The write queue is created once and preserved across reconnects, so we
	// capture it and block on it instead of polling with a sleep.
	var queue chan []byte
	for t.IsRunning() && queue == nil {
		t.Mu.Lock()
		if t.session != nil {
			queue = t.session.WriteQueue
		}
		t.Mu.Unlock()
		if queue == nil {
			time.Sleep(5 * time.Millisecond)
		}
	}
	if queue == nil {
		return
	}

	var pending []byte
	for t.IsRunning() {
		if pending == nil {
			// Done() has to be in this select, not just the for condition: a bare
			// receive parks here forever, and Stop() cannot release it. It would
			// only return if something later enqueued a packet, so every Start()
			// that found no session yet stacked another permanently parked
			// goroutine - each holding the WriteQueue and pinning its packets.
			// On mobile, where a transport is built per connection, that is one
			// leak per connection for the life of the process.
			select {
			case packet, ok := <-queue:
				if !ok {
					return
				}
				pending = packet
			case <-t.Done():
				return
			}
		}

		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			// Mid-reconnect: hold the packet and retry rather than drop it.
			time.Sleep(15 * time.Millisecond)
			continue
		}

		payload := base64.StdEncoding.EncodeToString(pending)
		msg := fmt.Sprintf(`42["message",{"type":"cursor","cursor":"18;%s"}]`, payload)
		if err := session.safeWrite(websocket.TextMessage, []byte(msg)); err != nil {
			utils.Debugf("[M-DOCS] Write error: %v", err)
			time.Sleep(15 * time.Millisecond)
			continue // keep pending; the reconnect will bring up a new conn
		}
		pending = nil
	}
}

// Stop also closes the document connection. BaseTransport.Stop only clears the
// running flag and the done channel: the reader goroutine would otherwise sit
// in ReadMessage until the server's next message, holding one of the two
// participant slots on a public Mail.ru link and still feeding CallReceive
// into a tunnel that is gone. Closing the socket unblocks the reader, which
// returns as soon as it sees !IsRunning().
func (t *MailruDocsTransport) Stop() error {
	err := t.BaseTransport.Stop()
	t.Mu.RLock()
	session := t.session
	t.Mu.RUnlock()
	if session != nil && session.Conn != nil {
		_ = session.Conn.Close()
	}
	return err
}

func (t *MailruDocsTransport) keepAliveLoop() {
	// tick(), not a raw <-ticker.C: the other three watchdogs already select on
	// Done(), and this one was the odd one out, so after Stop() the goroutine
	// lingered for a full KeepAliveInterval. Harmless at the 10s default, but a
	// long interval parks it for that long.
	keepAliveMsg := `42["message",{"type":"cursor","cursor":"18;---KA---"}]`

	for t.tick(t.GetConfig().KeepAliveInterval) {
		t.Mu.Lock()
		session := t.session
		t.Mu.Unlock()

		if session != nil && session.Conn != nil {
			if err := session.safeWrite(websocket.TextMessage, []byte(keepAliveMsg)); err != nil {
				// closeSession re-checks identity. A plain SetConnected(false)
				// here would blank the flag of a FRESH session if the
				// reconnect installed one while this write was in flight:
				// SetConnected(true) happens only at install, all watchdogs
				// skip while disconnected, and the new socket never errors,
				// so the tunnel would stay dead until the process restarts.
				if t.closeSession(session) {
					utils.Debugf("[M-DOCS] Keep-alive failed: %v", err)
				}
			}
		}
	}
}

func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {
	text := string(data)

	// Any inbound frame proves the channel is alive; reset the rx-idle clock.
	t.lastRx.Store(time.Now().UnixNano())

	if strings.Contains(text, "---KA---") {
		return
	}

	// Socket.IO ping - respond with pong
	if text == "2" {
		utils.Debugf("[M-DOCS] rx ping") // liveness evidence for rxIdleLoop
		if session != nil && session.Conn != nil {
			session.safeWrite(websocket.TextMessage, []byte("3"))
		}
		return
	}
	if text == "3" {
		return
	}

	if strings.Contains(text, `"type":"auth"`) && strings.Contains(text, `"result":1`) {
		// session is nil-checked two branches above, but not on every path
		// that can reach this line.
		if session == nil {
			return
		}
		utils.Debugf("[M-DOCS] Auth OK for user %s", session.UserID)
		return
	}

	if strings.Contains(text, "cursor") {
		base64Str := t.extractBase64String(text)
		if base64Str == "" {
			return
		}

		decoded, err := base64.StdEncoding.DecodeString(base64Str)
		if err != nil {
			utils.Debugf("[M-DOCS] Base64 decode error: %v", err)
			return
		}

		t.RecordReceive(len(decoded))
		t.lastRxData.Store(time.Now().UnixNano())
		t.CallReceive(decoded)
	}
}

// Watchdog constants. A healthy Mail.ru Socket.IO session receives the
// server's engine-level ping ("2") roughly every 25s even with no peers
// attached, so silence is a reliable liveness signal — unlike our own cursor
// markers, which the server never echoes back (verified 2026-09-24: "probe
// ok" count was 0 across all exits; a loopback probe forced a reconnect
// every ~90s and caused exactly the flapping it was meant to fix).
const (
	rxCheckEvery = 15 * time.Second
	rxIdleLimit  = 90 * time.Second

	// docKeyRecheckEvery: Mail.ru silently rotates the docKey of a public
	// link (verified 2026-09-25: an exit kept dialing KEYYpRUT1ErCo81pXJ
	// while fresh fetches returned KEY3nX3ek7ttW2ddC3). The peer refetches
	// on every tunnel start, so after a rotation the two sides land in
	// different "rooms": both see Auth OK, but zero relayed traffic.
	docKeyRecheckEvery = 5 * time.Minute

	// sessionRotateEvery forces a clean reconnect of our own session well
	// inside the window where long-lived sessions were observed to go stale
	// (working at 16h, dead after ~19h). Reconnect costs ~1s and the peer
	// re-dials with it.
	sessionRotateEvery = 6 * time.Hour

	// One-way channel detector: we handed tunnel bytes to the write queue
	// recently, but nothing came back for oneWayIdle — the peer's socket is
	// dead for reads while still accepting writes. A quiet tunnel has zero
	// on both counters, so this cannot fire on an idle link.
	//
	// oneWayPeerGrace guards against a false positive: a SYN to a blackholed
	// destination produces exactly the same "we send, nothing returns" shape
	// on a perfectly healthy socket. The peer-alive test is made ONCE, when
	// the timer is armed, and its verdict is then frozen - see healthLoop.
	// ponytail: a peer that spends 13 straight minutes (oneWayIdle 3m plus
	// oneWayCooldown 10m) talking to one unresponsive host after healthy
	// traffic still costs a ~1s blip, once per episode. Raise oneWayIdle
	// before adding real flow analysis.
	oneWayTxWindow  = 3 * time.Minute
	oneWayIdle      = 3 * time.Minute
	oneWayCooldown  = 10 * time.Minute
	oneWayPeerGrace = 10 * time.Minute
)

// closeSession drops a session only if it is still the live one.
//
// Every watchdog snapshots t.session under RLock and then acts on it without
// the lock. If connectToDoc installs a FRESH session in that window, the
// watchdog's unconditional SetConnected(false) would mark the new one down
// while closing the old, already-dead socket. Nothing sets the flag back
// (SetConnected(true) happens once, at install), all three watchdogs skip
// while IsConnected() is false, and the read loop never errors - a silent
// stall with a perfectly live socket. Re-reading the pointer under the lock
// and comparing identity closes that window.
func (t *MailruDocsTransport) closeSession(session *DocSession) bool {
	t.Mu.RLock()
	current := t.session
	t.Mu.RUnlock()
	if session == nil || current != session {
		return false
	}
	t.SetConnected(false)
	if session.Conn != nil {
		_ = session.Conn.Close()
	}
	return true
}

// tick waits for the next tick or for Stop(). Without the Done() case a
// watchdog lives on until its next tick - up to 5 minutes for docKeyLoop.
func (t *MailruDocsTransport) tick(interval time.Duration) bool {
	// A non-positive interval used to panic under time.NewTicker, which at least
	// failed loudly. time.NewTimer(0) instead fires immediately, so a
	// misconfigured KeepAliveInterval would turn this watchdog into a 100% CPU
	// spin - strictly worse than the crash it replaced. Clamp instead.
	if interval <= 0 {
		interval = time.Second
	}
	timer := time.NewTimer(interval)
	defer timer.Stop()
	select {
	case <-timer.C:
		return t.IsRunning()
	case <-t.Done():
		return false
	}
}

// rxIdleLoop tears down a half-dead WebSocket: connected, but nothing (not
// even the server's ping) has arrived for rxIdleLimit. Closing the socket
// makes the read loop notice and run the normal reconnect path.
func (t *MailruDocsTransport) rxIdleLoop() {
	for t.IsRunning() {
		if !t.tick(rxCheckEvery) {
			return
		}
		if !t.IsConnected() {
			continue
		}
		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			continue
		}
		// Sampled AFTER the snapshot so the age cannot describe a newer
		// session than the one we are about to close.
		idle := time.Since(time.Unix(0, t.lastRx.Load()))
		if idle <= rxIdleLimit {
			continue
		}
		if t.closeSession(session) {
			utils.Debugf("[M-DOCS] rx idle %v > %v, forcing reconnect", idle.Round(time.Second), rxIdleLimit)
		}
	}
}

// docKeyLoop re-fetches the public doc every docKeyRecheckEvery and forces a
// reconnect when Mail.ru hands out a NEW DocKey than the one our live session
// dialed. The reconnect path refetches anyway, so it lands in the fresh room.
// Fetch errors are logged but never tear down a healthy session — only a
// confirmed key mismatch does.
func (t *MailruDocsTransport) docKeyLoop() {
	for t.IsRunning() {
		if !t.tick(docKeyRecheckEvery) {
			return
		}
		if !t.IsConnected() {
			continue
		}
		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			continue
		}

		info, err := t.fetchDocInfo(t.weblink)
		if err != nil {
			utils.Debugf("[M-DOCS] doc key recheck failed (keeping session): %v", err)
			continue
		}
		if info.DocKey == "" || info.DocKey == session.Info.DocKey {
			continue
		}
		// closeSession re-checks identity: a reconnect that swapped the
		// session while we were fetching would otherwise make us close the
		// new socket on the strength of the old one's key.
		if t.closeSession(session) {
			utils.Debugf("[M-DOCS] doc key rotated %s -> %s, forcing reconnect to fresh room",
				session.Info.DocKey, info.DocKey)
		}
	}
}

// healthLoop runs two preventive checks on the live session:
//
//  1. sessionRotateEvery — recycle the session. Long-lived sessions (16h+)
//     were observed to go stale; a 6h rotation keeps us inside the window
//     where the relay still works. Costs ~1s of downtime every 6h.
//  2. one-way channel — we are sending tunnel bytes but the peer sends
//     nothing back. The peer's socket died for reads (verified 2026-09-27 on
//     a phone: its SYNs reached us, our SYN-ACKs were never ACKed).
//     Reconnecting our own socket cannot revive the peer's read path, but it
//     drops our half-open session so the peer re-dials and the room state is
//     rebuilt.
func (t *MailruDocsTransport) healthLoop() {
	for t.IsRunning() {
		if !t.tick(rxCheckEvery) {
			return
		}
		if !t.IsConnected() {
			t.oneWaySince.Store(0)
			continue
		}
		t.Mu.RLock()
		session := t.session
		t.Mu.RUnlock()
		if session == nil || session.Conn == nil {
			continue
		}

		now := time.Now()
		drop := func(reason string) {
			if t.closeSession(session) {
				utils.Debugf("[M-DOCS] %s", reason)
			}
		}

		if start := t.sessionStart.Load(); start > 0 && now.Sub(time.Unix(0, start)) > sessionRotateEvery {
			drop("session rotation (>6h), reconnecting")
			continue
		}

		tx := t.lastTxData.Load()
		rx := t.lastRxData.Load()
		if tx == 0 || rx == 0 {
			continue // no tunnel traffic seen yet on this session
		}
		// Only judge when we recently wrote and the peer went quiet while
		// it should be answering.
		if now.Sub(time.Unix(0, tx)) > oneWayTxWindow {
			continue
		}
		if now.Sub(time.Unix(0, rx)) <= oneWayIdle {
			t.peerAlive.Store(now.UnixNano())
			t.oneWaySince.Store(0)
			continue
		}
		since := t.oneWaySince.Load()
		if since == 0 {
			// The "was the peer actually alive" test runs HERE, once, and its
			// verdict is frozen by arming the timer. Re-checking it later
			// cannot work: the silence we are measuring is itself what ages the
			// evidence, so peerAlive would cross the grace window exactly when
			// the cooldown expires, the guard would trip every time, and the
			// detector would never fire. (oneWayIdle 3m + oneWayCooldown 10m is
			// already longer than the 10m grace.)
			if now.Sub(time.Unix(0, t.peerAlive.Load())) > oneWayPeerGrace {
				continue // the peer never proved itself; leave the session alone
			}
			t.oneWaySince.Store(now.UnixNano())
			continue
		}
		if now.Sub(time.Unix(0, since)) < oneWayCooldown {
			continue
		}
		drop("one-way channel: sent tunnel data, peer silent >3m, reconnecting")
		t.oneWaySince.Store(0)
	}
}

func (t *MailruDocsTransport) extractBase64String(response string) string {
	matches := cursorPayloadRe.FindStringSubmatch(response)
	if len(matches) > 1 {
		return matches[1]
	}
	return ""
}

func (t *MailruDocsTransport) scheduleReconnect(attempt int) {
	next := attempt + 1
	if !t.IsRunning() || next >= t.GetConfig().MaxReconnectAttempts {
		return
	}

	d := reconnectBackoff(next)
	utils.Debugf("[M-DOCS] reconnecting in %v (attempt %d)", d, next)
	// Wait on Done() rather than sleeping: with the 15s cap plus 50% jitter a
	// plain Sleep left a ~22s reconnect loop running after Stop.
	select {
	case <-time.After(d):
	case <-t.Done():
		return
	}
	if !t.IsRunning() {
		return
	}

	t.RecordReconnect()
	t.connectToDoc(next)
}

// reconnectBackoff returns an exponential backoff with jitter, capped at 15s.
func reconnectBackoff(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	shift := n - 1
	if shift > 5 {
		shift = 5
	}
	d := 500 * time.Millisecond * time.Duration(1<<uint(shift))
	if d > 15*time.Second {
		d = 15 * time.Second
	}
	// add up to +50% jitter
	d += time.Duration(rand.Int63n(int64(d/2) + 1))
	return d
}

// fetchDocInfo POSTs to Mail.ru's public-document editor API and parses the
// response into the fields needed to open the collaborative WebSocket.
func (t *MailruDocsTransport) fetchDocInfo(weblink string) (MailruDocsInfo, error) {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		var err error
		jar, err = cookiejar.New(nil)
		if err != nil {
			return MailruDocsInfo{}, err
		}
	}
	client := &http.Client{Jar: jar, Timeout: 15 * time.Second}

	reqBody := map[string]string{
		"x-email":  "anonym",
		"public":   "/" + weblink,
		"platform": "desktop_web",
	}
	jsonData, _ := json.Marshal(reqBody)

	apiURL := "https://cloud.mail.ru/api/v4/r7/edit"
	utils.Debugf("[M-DOCS] fetchDocInfo POST %s", apiURL)

	req, _ := http.NewRequest("POST", apiURL, bytes.NewBuffer(jsonData))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("User-Agent", mailruUserAgent)
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", weblink, weblink))

	resp, err := client.Do(req)
	if err != nil {
		return MailruDocsInfo{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return MailruDocsInfo{}, fmt.Errorf("API returned status %d", resp.StatusCode)
	}

	bodyBytes, _ := io.ReadAll(resp.Body)

	var res map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &res); err != nil {
		return MailruDocsInfo{}, fmt.Errorf("failed to parse JSON: %w", err)
	}

	apiBase, _ := res["api"].(string)
	token, _ := res["token"].(string)

	document, ok := res["document"].(map[string]interface{})
	if !ok || document == nil {
		return MailruDocsInfo{}, fmt.Errorf("document object missing")
	}

	docKey, _ := document["key"].(string)
	fileType, _ := document["fileType"].(string)
	docURL, _ := document["url"].(string)
	docTitle, _ := document["title"].(string)
	// document.permissions is an object of booleans (comment/edit/download/…),
	// not a number - sending it as anything else makes the editor server
	// reject the auth message with "access deny".
	permissions, _ := document["permissions"].(map[string]interface{})
	if permissions == nil {
		permissions = make(map[string]interface{})
	}

	editorConfig, ok := res["editorConfig"].(map[string]interface{})
	if !ok || editorConfig == nil {
		return MailruDocsInfo{}, fmt.Errorf("editorConfig object missing")
	}
	callbackURL, _ := editorConfig["callbackUrl"].(string)

	userObj, _ := editorConfig["user"].(map[string]interface{})
	var editorUserID string
	if userObj != nil {
		editorUserID, _ = userObj["id"].(string)
	}

	wsBase := strings.Replace(apiBase, "https://", "wss://", 1)
	wsURL := fmt.Sprintf("%s/doc/%s/c/?EIO=4&transport=websocket", wsBase, docKey)

	return MailruDocsInfo{
		Token:        token,
		DocKey:       docKey,
		WsURL:        wsURL,
		FileType:     fileType,
		DocURL:       docURL,
		DocTitle:     docTitle,
		Permissions:  permissions,
		CallbackURL:  callbackURL,
		EditorUserID: editorUserID,
	}, nil
}

func randUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}

// ---- CookieExchanger ----

// FetchCookies returns a snapshot of the transport's current cookie jar as
// name -> value. Used by the exit node to answer a SubtypeCookiesRequest.
func (t *MailruDocsTransport) FetchCookies() (map[string]string, error) {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		return nil, fmt.Errorf("mailru: cookie jar is nil")
	}
	u, err := url.Parse("https://cloud.mail.ru/")
	if err != nil {
		return nil, err
	}
	out := make(map[string]string)
	for _, c := range jar.Cookies(u) {
		out[c.Name] = c.Value
	}
	return out, nil
}

// ApplyCookies replaces the transport's cookie jar with the provided values
// and forces the current session to reconnect.
func (t *MailruDocsTransport) ApplyCookies(values map[string]string) error {
	if len(values) == 0 {
		return nil
	}
	u, _ := url.Parse("https://cloud.mail.ru/")
	jar, _ := cookiejar.New(nil)
	cookies := make([]*http.Cookie, 0, len(values))
	for k, v := range values {
		cookies = append(cookies, &http.Cookie{Name: k, Value: v, Path: "/"})
	}
	jar.SetCookies(u, cookies)

	t.jarMu.Lock()
	t.cookieJar = jar
	t.jarMu.Unlock()

	utils.Debugf("[M-DOCS] applied %d cookies, forcing reconnect", len(cookies))

	t.Mu.Lock()
	session := t.session
	t.session = nil
	t.SetConnected(false)
	t.Mu.Unlock()
	if session != nil && session.Conn != nil {
		_ = session.Conn.Close()
	}
	if t.IsRunning() {
		t.scheduleReconnect(0)
	}
	return nil
}
