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
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"

	"openflux/netbind"
	"openflux/transport"
	"openflux/utils"
)

// A current UA, and the other half of the fix for the 409: Mail.ru's edge
// refuses the upgrade when it sees the older Chrome/137 string as well. One
// constant serves both the REST API and the upgrade, so both always agree.
const mailruUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

// mailruEditAPI is the editor API the transport posts the document info to.
const mailruEditAPI = "https://cloud.mail.ru/api/v4/r7/edit"

// maxWSMessageBytes caps one WebSocket message. A tunnel frame is at most 1 MiB
// (transport/framing.go), so a few megabytes leaves generous headroom for a
// batched read while still refusing the unbounded message a hostile
// participant can build out of continuation frames.
const maxWSMessageBytes = 4 << 20

// safeMailruWSURL keeps only the scheme and host. The document's WebSocket URL
// carries the doc key in its path, and the doc key is the room's address:
// anyone holding it can join. Printing the whole URL is the same leak as
// printing the key.
func safeMailruWSURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "<invalid URL>"
	}
	return u.Scheme + "://" + u.Host + "/…"
}

// canEdit reports whether the session may write to the document.
//
// A document that only lets the session read ("edit": false in the
// permissions) is closed by the editor server the first time a write that
// touches the document reaches it - the upstream project found the same and
// guards its save the same way. Sending a cursor into such a document costs
// the session its socket every ten seconds, which looks like a flaky link
// rather than a document that refuses writes.
func canEdit(permissions map[string]interface{}) bool {
	if permissions == nil {
		return true
	}
	if v, ok := permissions["edit"].(bool); ok {
		return v
	}
	return true
}

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
	// Mode is the mode the server granted: "edit" or "view". A document whose
	// share settings give the session no editing rights answers "view" and
	// permissions.edit=false, and asking for "edit" on it makes the editor
	// server drop the session right after the upgrade.
	Mode string
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
	// A write into a half-open connection (NAT dropped it, the network changed)
	// blocks until the kernel gives up - minutes - and it holds writeMu for all
	// of it, so writerLoop AND keepAliveLoop freeze behind it. The interface
	// stays up, the tunnel is dark, and nothing asks for a reconnect because
	// the only code that could is the one that is stuck.
	_ = s.Conn.SetWriteDeadline(time.Now().Add(docWriteTimeout))
	return s.Conn.WriteMessage(messageType, data)
}

// docWriteTimeout bounds one WebSocket write to the document.
const docWriteTimeout = 20 * time.Second

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

	// reconnecting keeps one reconnect in flight. A single network drop is
	// seen by several goroutines at once: the reader blocked in ReadMessage,
	// the keep-alive writer, and the rx-idle watchdog. Each of them now
	// schedules a reconnect, and without this guard they would dial three
	// fresh sessions, each displacing and closing the previous one - the
	// self-sustaining loop that closeSession's identity check was written to
	// stop. Whoever loses the race simply does nothing; a reconnect is
	// already on its way.
	reconnecting transport.ReconnectGuard

	// Consecutive sessions that died before healthySession. Read and written
	// atomically; only the backoff consults it.
	shortSessionCount int32

	// Where the two reconnect entry points are on the backoff ladder, shared so
	// that neither can quietly restart it at the bottom.
	attempt int32
}

// currentAttempt is the ladder position both reconnect paths must continue from.
func (t *MailruDocsTransport) currentAttempt() int {
	n := atomic.LoadInt32(&t.attempt)
	if n < 0 {
		return 0
	}
	return int(n)
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
	// Drop the session the previous run left behind. connectToDoc() reads
	// t.session to decide whether to spawn the writer, so a stale non-nil
	// pointer after Stop() → Start() made it skip the spawn: nothing was draining
	// the queue and every Send() returned "write queue full" until the next
	// reconnect happened to replace the session. Unreachable today because
	// nothing restarts a live transport, and a silent permanent write failure
	// if anything ever does.
	t.Mu.Lock()
	t.session = nil
	t.Mu.Unlock()
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
		// This attempt now owns the reconnect. Clearing the guard here is what
		// lets a FAILED attempt schedule the next one: scheduleReconnect set
		// the flag before calling us, and without this the failure path would
		// find it still set, skip itself as "already in flight", and leave the
		// tunnel down.
		t.reconnecting.Release()
		defer func() {
			if r := recover(); r != nil {
				utils.Infof("[PANIC] recovered in mailru.connect: %v\n%s", r, debug.Stack())
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
			// A throttled address gets minutes, not seconds: see
			// ErrRateLimited. Retrying it fast is what deepened the throttle.
			if errors.Is(err, ErrRateLimited) {
				t.scheduleReconnectNoCaptcha(attempt)
				return
			}
			t.scheduleReconnect(attempt)
			return
		}

		dialer := websocket.Dialer{
			// gorilla/websocket only offers Sec-WebSocket-Extensions:
			// permessage-deflate when this is set, and Mail.ru's edge refuses
			// the upgrade with 409 when that header is absent. Measured on the
			// live service with scripts/wsprobe: old UA without compression
			// answers 409, and either change on its own answers 101.
			//
			// The service does not answer with the extension, so nothing is
			// actually compressed: no CPU is spent on an already encrypted and
			// batched payload, and SetReadLimit below still counts the same
			// bytes it always did.
			//
			// Two things would change if it ever did. gorilla counts the read
			// limit in wire bytes, so a compressed frame could expand past
			// maxWSMessageBytes. And gorilla refuses the whole handshake if a
			// server answers permessage-deflate without both
			// *_no_context_takeover params, turning a working link into a hard
			// failure. scripts/wsprobe checks the negotiated response, so run it
			// if this transport starts failing the upgrade.
			EnableCompression: true,
			HandshakeTimeout:  15 * time.Second,
			NetDialContext: netbind.Wrap(&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		}
		headers := http.Header{}
		headers.Set("User-Agent", mailruUserAgent)
		headers.Set("Origin", "https://docs.datacloudmail.ru")
		// Accept-Language on the upgrade, matching what the browser sends. The
		// upstream project added the same header for the same reason after
		// 409s on the upgrade (their commit "send Accept-Language on the Docs
		// WebSocket and API", #164), and our edge here answers 409 to every new
		// session from every host we tried. Whether this header is what makes
		// the difference is UNVERIFIED here: it is a browser-fidelity change
		// with a matching upstream report, not a measured cause. Treat the next
		// 409 after this as "not fixed" rather than as a partial success.
		headers.Set("Accept-Language", "en-US,en;q=0.9")

		utils.Debugf("[M-DOCS] WebSocket dial %s", safeMailruWSURL(info.WsURL))
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
		// The document is public: anyone can join this room and send. Without a
		// read limit gorilla buffers a whole continuation-framed message into
		// memory, and ReadMessage copies it twice more, so one participant can
		// exhaust the host with a frame that need not even be valid.
		conn.SetReadLimit(maxWSMessageBytes)
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

		// The server tells us which mode the session may use; a session that
		// asks for more than it was granted is dropped right after the
		// upgrade.
		mode := info.Mode
		if mode == "" {
			mode = "edit"
		}
		authMsg := map[string]interface{}{
			"type":                "auth",
			"docid":               info.DocKey,
			"documentCallbackUrl": info.CallbackURL,
			// The upstream project sends a fixed placeholder here and the
			// real session token in jwtOpen below, and the editor accepts
			// exactly that pair - a signed-in session included.
			"token": "fghhfgsjdgfjs",
			"user": map[string]interface{}{
				"id":        info.EditorUserID,
				"username":  userID,
				"indexUser": -1,
			},
			"editorType":         0,
			"lastOtherSaveTime":  -1,
			"block":              []interface{}{},
			"documentFormatSave": 65,
			"view":               mode == "view",
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
			"mode":                  mode,
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

				// A session that died young is not a network blip, it is a
				// service refusing us - and reconnecting every fifteen seconds
				// is what turns a refusal into a ban.
				t.noteSessionEnd(time.Since(connectedAt))

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
			if isFatalWriteError(err) {
				// gorilla's own rule: after a write timeout the connection's
				// frame state is corrupt and must never be written to again.
				// Retrying on the same socket turns a 20 s bound into a 20 s
				// spin that can still lose the frame boundary, so the socket
				// is dropped here.
				//
				// Recovery is NOT requested from here, deliberately. The writer
				// holds `pending` and retries every 15 ms; a self-requested
				// reconnect would be re-armed on every one of those retries for
				// the whole fetchDocInfo+dial window, each pass resetting the
				// attempt counter, so MaxReconnectAttempts would never trip and
				// the carrier API would take a connect attempt every 15 ms -
				// the rate-limit and ban condition this work exists to avoid.
				// Closing the socket is what wakes the reader, and only the
				// reader that owned the live session may reconnect for it; the
				// keep-alive watchdog is the backstop if it is already gone.
				utils.Debugf("[M-DOCS] connection is corrupt after a write failure, dropping it")
				t.closeSession(session)
			}
			time.Sleep(15 * time.Millisecond)
			continue // keep pending; the reconnect will bring up a new conn
		}
		pending = nil
	}
}

// isFatalWriteError reports whether a write failure means this connection can
// no longer be used. A timeout is fatal by gorilla's contract; a close is the
// socket already gone, and anything else may well be transient.
//
// A reset or a broken pipe is the socket gone too, and it is what a dropped
// NAT produces on the NEXT write - the commonest failure here by a distance.
// Left out, it read as "transient", so the writer kept hammering a dead socket
// every 15 ms and no reconnect was ever scheduled: the exact half-open state
// this function exists to end.
func isFatalWriteError(err error) bool {
	if err == nil {
		return false
	}
	if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
		return true
	}
	if errors.Is(err, net.ErrClosed) ||
		errors.Is(err, syscall.ECONNRESET) ||
		errors.Is(err, syscall.ECONNABORTED) ||
		errors.Is(err, syscall.EPIPE) ||
		errors.Is(err, syscall.ENOTCONN) {
		return true
	}
	return websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway)
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
					t.requestReconnect("keep-alive write failed")
				}
			}
		}
	}
}

func (t *MailruDocsTransport) handleMessage(session *DocSession, data []byte) {
	text := string(data)

	// Any inbound frame proves the channel is alive; reset the rx-idle clock.
	t.lastRx.Store(time.Now().UnixNano())

	// A frame can carry a keep-alive AND data, so the cursor branch below
	// filters the keep-alives per entry rather than this frame being dropped
	// whole. Only a frame that is nothing but a keep-alive stops here.
	if strings.Contains(text, "---KA---") && !strings.Contains(text, "cursor") {
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

	// The other half of the answer, and the reason the wait used to be
	// useless. Before this branch existed only "result":1 was handled: a
	// rejection - wrong encryption key, a document that no longer grants
	// access, a key from another profile - fell through every `if` below and
	// left nothing in the log at all. The client then sat on
	// "Транспорт не подключился за 30 секунд" with no way to tell a wrong key
	// from a document nobody is in, which is the single longest thing to
	// diagnose by hand. Logged, not counted: the reconnect loop already owns
	// what happens next, and the diagnostic on the app side reads this line.
	if strings.Contains(text, `"type":"auth"`) {
		// The server's own words, capped. The raw frame also carries the
		// cursor and the session id, and those are not the user's business.
		reason := "отказано без пояснения"
		if i := strings.Index(text, `"reason"`); i >= 0 {
			tail := text[i+len(`"reason"`):]
			tail = strings.TrimPrefix(tail, ":")
			if end := strings.IndexAny(tail, `",}`); end >= 0 {
				reason = strings.TrimSpace(tail[:end])
			}
		}
		utils.Debugf("[M-DOCS] Auth REJECTED: %s", reason)
		return
	}

	if strings.Contains(text, "cursor") {
		for _, base64Str := range CursorPayloads(text) {
			decoded, err := base64.StdEncoding.DecodeString(base64Str)
			if err != nil {
				utils.Debugf("[M-DOCS] Base64 decode error: %v", err)
				continue
			}
			t.RecordReceive(len(decoded))
			t.lastRxData.Store(time.Now().UnixNano())
			t.CallReceive(decoded)
		}
	}
}

// CursorPayloads returns the base64 payload of every cursor entry in a server
// message, in order, without the keep-alive entries.
//
// Every entry, not the first one: Mail.ru batches several cursor entries into a
// single frame under load, and a keep-alive may sit anywhere among them. The
// first version returned early on a frame containing "---KA---" and took a
// single submatch, so under load every packet after the first in a batched
// frame was dropped without a word. Pure function, so it is testable without a
// socket; cursor_test.go covers the batching cases.
func CursorPayloads(text string) []string {
	var out []string
	for _, m := range cursorPayloadRe.FindAllStringSubmatch(text, -1) {
		if len(m) > 1 && m[1] != "---KA---" {
			out = append(out, m[1])
		}
	}
	return out
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
			t.requestReconnect(fmt.Sprintf("no inbound frame for %v", idle.Round(time.Second)))
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
			// The doc key is the room's address: anyone holding it can join. Log the
			// digests, which is all that is needed to see that a rotation happened.
			utils.Debugf("[M-DOCS] doc key rotated %s -> %s, forcing reconnect to fresh room",
				utils.Sha256Hex([]byte(session.Info.DocKey))[:12],
				utils.Sha256Hex([]byte(info.DocKey))[:12])
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
				t.requestReconnect(reason)
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

// advanceAttempt publishes and returns the next position on the ladder. Both
// reconnect entry points go through it, so neither can restart the ladder at
// the bottom while the other is mid-flight.
func (t *MailruDocsTransport) advanceAttempt(attempt int) int {
	next := attempt + 1
	atomic.StoreInt32(&t.attempt, int32(next))
	return next
}

func (t *MailruDocsTransport) scheduleReconnect(attempt int) {
	// Publish the position before the guard and the wait, so a watchdog that
	// fires mid-flight continues this ladder instead of starting a second one.
	next := t.advanceAttempt(attempt)
	if !t.IsRunning() {
		return
	}
	if next >= t.GetConfig().MaxReconnectAttempts {
		// This used to return in silence. The transport then stayed down for
		// good: the TUN interface remained up, so Android kept reporting
		// "connected" and the phone kept routing into it, while no session
		// existed. Observed 2026-09-29 on a phone over Beeline LTE - the
		// tunnel went dark on a network drop and only came back when the user
		// toggled the VPN by hand. Say it, so the next occurrence is one log
		// line instead of a mystery.
		utils.Debugf("[M-DOCS] giving up after %d reconnect attempts, max is %d; "+
			"the tunnel is down and will not recover on its own", next, t.GetConfig().MaxReconnectAttempts)
		return
	}
	if !t.reconnecting.TryClaim() {
		utils.Debugf("[M-DOCS] reconnect already in flight, not scheduling another")
		return
	}
	t.RecordReconnect()

	d := t.effectiveWait(next)
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

// requestReconnect lets a watchdog recover a session instead of relying on the
// reader goroutine. closeSession only flips the connected flag and closes the
// socket: on its own that leaves recovery to whoever is blocked in
// ReadMessage. When nobody is - a reader that already returned, or a socket
// whose peer vanished without a FIN - the tunnel stayed down indefinitely with
// the interface still up. Every caller of closeSession outside the reader goes
// through here.
func (t *MailruDocsTransport) requestReconnect(reason string) {
	if !t.IsRunning() {
		return
	}
	utils.Debugf("[M-DOCS] session dead (%s), reconnecting through the backoff", reason)
	// Watchdogs used to dial immediately, which is right for a network blip but
	// wrong for a throttled document: the immediate dial bypasses the wait that
	// is supposed to spare the address, and when several watchdogs fire in a
	// burst each one slips a fetchDocInfo past the next. Measured 2026-10-12:
	// ~90 dials an hour, pairs of them milliseconds apart. Going through the
	// same backoff as the reader collapses them into one. scheduleReconnect owns
	// the attempt counter and the guard, so the ladder is shared and single.
	t.RecordReconnect()
	t.scheduleReconnect(t.currentAttempt())
}

// ErrRateLimited signals that the editor API answered with Mail.ru's anti-abuse
// challenge page rather than JSON. Retrying it on the normal backoff is the one
// thing that must not happen: the backoff is seconds, the penalty is minutes,
// so the retries are what put the address into this state.
var ErrRateLimited = errors.New("mailru docs: rate limited by the editor API")

// isChallengePage recognises that page. The client follows the 302, so the
// status line is useless; the final URL and the body's own markers are not.
func isChallengePage(resp *http.Response, body []byte) bool {
	if resp == nil {
		return false
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		return true
	}
	if resp.Request != nil && resp.Request.URL != nil {
		p := strings.ToLower(resp.Request.URL.Path)
		if strings.Contains(p, "challenge") || strings.Contains(p, "captcha") {
			return true
		}
	}
	head := body
	if len(head) > 4096 {
		head = head[:4096]
	}
	s := strings.ToLower(string(head))
	return strings.Contains(s, "hash429") ||
		strings.Contains(s, "challenge.html") ||
		strings.Contains(s, "smartcaptcha")
}

// scheduleReconnectNoCaptcha backs off to a fixed long wait instead of the
// growing one. While the address is throttled every attempt is wasted and makes
// the throttle worse; a few minutes of quiet is what actually clears it.
func (t *MailruDocsTransport) scheduleReconnectNoCaptcha(attempt int) {
	if !t.IsRunning() {
		return
	}
	if !t.reconnecting.TryClaim() {
		return
	}
	const wait = rateLimitReconnectWait
	utils.Debugf("[M-DOCS] rate limited, waiting %v before the next attempt", wait)
	select {
	case <-time.After(wait):
	case <-t.Done():
		return
	}
	if !t.IsRunning() {
		return
	}
	t.RecordReconnect()
	t.connectToDoc(0)
}

// effectiveWait is how long to sit before the next dial. The exponential
// backoff caps at 15 s because a network blip must not delay recovery. That
// reasoning does not hold when every session dies in seconds: measured on
// 2026-10-12, Mail.ru never let one past 9.8 s, and the node dutifully
// re-dialled about every twenty seconds for hours - which is the way to turn a
// refusal into a ban. While sessions keep coming back short, wait minutes.
func (t *MailruDocsTransport) effectiveWait(attempt int) time.Duration {
	d := reconnectBackoff(attempt)
	if c := shortSessionCooldown(t.shortSessions()); c > d {
		utils.Debugf("[M-DOCS] %d short-lived sessions in a row; waiting %v instead of %v",
			t.shortSessions(), c, d)
		d = c
	}
	return d
}

// noteSessionEnd records how long a session lasted and forgets the run of
// short ones the moment a session lives long enough to count as a working one.
func (t *MailruDocsTransport) noteSessionEnd(alive time.Duration) {
	if alive < healthySession {
		atomic.AddInt32(&t.shortSessionCount, 1)
		return
	}
	atomic.StoreInt32(&t.shortSessionCount, 0)
}

func (t *MailruDocsTransport) shortSessions() int32 {
	return atomic.LoadInt32(&t.shortSessionCount)
}

// rateLimitReconnectWait is how long to sit out a document that is actively
// refusing us. shortSessionCooldown tops out at the same value on purpose: one
// rule for "this service is saying no", not two that can disagree.
const rateLimitReconnectWait = 5 * time.Minute

// healthySession is the lifetime past which a session counts as a working one.
// Below it the service is refusing us rather than the network having blinked.
const healthySession = 30 * time.Second

// shortSessionCooldown is how long to wait after n consecutive sessions that
// all died young. The first costs nothing extra - the ordinary backoff already
// covers a single blip - and from the second on it grows to minutes.
//
// The floor is 30 s and not something smaller on purpose: the ordinary backoff
// reaches 22.5 s once its 50% jitter is added, so a cooldown under that would
// be silently swallowed and the escalation would depend on a random draw.
func shortSessionCooldown(n int32) time.Duration {
	if n < 2 {
		return 0
	}
	shift := n - 2
	if shift > 8 {
		shift = 8
	}
	d := 30 * time.Second * time.Duration(1<<uint(shift))
	if d > rateLimitReconnectWait {
		d = rateLimitReconnectWait
	}
	return d
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
// mailruCSRFPattern picks the CSRF token out of the state the document's page
// carries: "tokens":{"csrf":"..."}.
var mailruCSRFPattern = regexp.MustCompile(`"tokens"\s*:\s*\{\s*"csrf"\s*:\s*"([^"]+)"`)

// editAPIRequest posts the editor API request, with the page's CSRF token
// when one is known.
func (t *MailruDocsTransport) editAPIRequest(client *http.Client, body []byte, weblink, csrf string) (*http.Response, []byte, error) {
	req, _ := http.NewRequest("POST", mailruEditAPI, bytes.NewBuffer(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("User-Agent", mailruUserAgent)
	// The same browser header the upgrade sends; upstream sends it here too.
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", weblink, weblink))
	if csrf != "" {
		req.Header.Set("X-CSRF-Token", csrf)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	bodyBytes, _ := io.ReadAll(resp.Body)
	return resp, bodyBytes, nil
}

// documentCSRF reads the document's page and takes the CSRF token out of the
// state the page carries. The page also hands out the session cookies the jar
// keeps, so this is the step that turns an anonymous client into a signed-in
// one.
func (t *MailruDocsTransport) documentCSRF(client *http.Client, weblink string) (string, error) {
	pageURL := fmt.Sprintf("https://cloud.mail.ru/public/%s", weblink)
	req, _ := http.NewRequest("GET", pageURL, nil)
	req.Header.Set("User-Agent", mailruUserAgent)
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("Accept", "text/html,application/xhtml+xml")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	page, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("document page returned status %d", resp.StatusCode)
	}
	m := mailruCSRFPattern.FindSubmatch(page)
	if m == nil {
		return "", fmt.Errorf("no CSRF token in the document page")
	}
	return string(m[1]), nil
}

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

	utils.Debugf("[M-DOCS] fetchDocInfo POST %s", mailruEditAPI)

	// A document whose share settings allow anonymous viewing but not
	// anonymous editing answers 401 "public is available with auth only".
	// With a Mail.ru session present it answers 403 {"error":"token"} instead,
	// and the token it wants is the CSRF token the document's own page carries
	// in "tokens":{"csrf":...}. Measured 2026-10-22 from a German host: with
	// that header the same request answers 200 and the document opens. The
	// anonymous path is untouched - the page is only read after the plain
	// request was refused.
	resp, bodyBytes, err := t.editAPIRequest(client, jsonData, weblink, "")
	if err != nil {
		return MailruDocsInfo{}, err
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		csrf, csrfErr := t.documentCSRF(client, weblink)
		if csrfErr != nil {
			return MailruDocsInfo{}, fmt.Errorf("API returned status %d and the document page could not be read: %w",
				resp.StatusCode, csrfErr)
		}
		utils.Debugf("[M-DOCS] editor API answered %d; retrying with the page's CSRF token", resp.StatusCode)
		resp, bodyBytes, err = t.editAPIRequest(client, jsonData, weblink, csrf)
		if err != nil {
			return MailruDocsInfo{}, err
		}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return MailruDocsInfo{}, fmt.Errorf("API returned status %d", resp.StatusCode)
	}

	// Mail.ru answers an editor-API call from a throttled address with its
	// anti-abuse page instead of JSON: a 302 to /challenge.html?hash429=...,
	// which the default client follows, so by here the status is 200 and the
	// body is HTML. Left as "failed to parse JSON" it read as a transient
	// glitch and the reconnect backoff retried every few seconds, which is
	// what keeps an address throttled - a node that could have recovered in
	// five minutes of quiet instead spent an hour making it worse.
	if isChallengePage(resp, bodyBytes) {
		utils.Debugf("[M-DOCS] editor API answered with the anti-abuse challenge " +
			"(rate limited); backing off instead of retrying")
		return MailruDocsInfo{}, ErrRateLimited
	}

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
	mode, _ := editorConfig["mode"].(string)
	if mode == "" {
		mode = "edit"
	}

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
		Mode:         mode,
	}, nil
}

func randUserID() string {
	return fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
}

// ---- CookieExchanger ----

// LoadCookieFile imports a Netscape cookies.txt export into the transport's
// cookie jar, so the document opens signed in. Needed for documents whose
// share settings allow anonymous viewing but not anonymous editing: the editor
// API answers those with 401 "public is available with auth only", and only a
// Mail.ru session gets past it.
func (t *MailruDocsTransport) LoadCookieFile(path string) error {
	t.jarMu.RLock()
	jar := t.cookieJar
	t.jarMu.RUnlock()
	if jar == nil {
		jar, _ = cookiejar.New(nil)
		t.jarMu.Lock()
		t.cookieJar = jar
		t.jarMu.Unlock()
	}
	return loadMailruCookies(path, jar)
}

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
