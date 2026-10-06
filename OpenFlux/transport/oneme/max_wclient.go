package oneme

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/gorilla/websocket"

	"openflux/utils"
)

const (
	WS_HOST     = "wss://ws-api.oneme.ru/websocket"
	RPC_VERSION = 11
	USER_AGENT  = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
)

func NewMaxClient() *MaxClient {
	return &MaxClient{
		deviceID:      genUUID(),
		keepaliveStop: make(chan struct{}),
	}
}

func (c *MaxClient) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	header := http.Header{}
	header.Set("Origin", "https://web.max.ru")
	header.Set("User-Agent", USER_AGENT)
	conn, _, err := websocket.DefaultDialer.Dial(WS_HOST, header)
	if err != nil {
		return err
	}
	// The only transport in the tree with neither a read limit nor a deadline.
	// gorilla allocates whatever frame arrives, and a peer that never sends
	// another byte pins this goroutine forever - on the one transport that has
	// to survive unattended to receive a call.
	conn.SetReadLimit(maxWSFrameBytes)
	c.conn = conn
	utils.SafeGo("oneme.wread", func() { c.readLoop() })
	utils.Debugf("[MAX] connected")
	return nil
}

func (c *MaxClient) SetEventCallback(cb func(MaxPacket)) { c.onEvent = cb }

// IsConnected reports a live socket, not merely an attempted one. The caller
// decides whether to fail over to another transport, so a claim that cannot be
// true is worse than no claim: the user is left with a dead tunnel and no
// explanation.
func (c *MaxClient) IsConnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn != nil && c.loggedIn
}

func (c *MaxClient) readLoop() {
	defer func() {
		if r := recover(); r != nil {
			logError("recovered in MaxClient.readLoop: %v", r)
		}
	}()
	for {
		// Cleared immediately after: this connection is long-lived and the
		// next frame may legitimately be a call that arrives minutes later.
		_ = c.conn.SetReadDeadline(time.Now().Add(wsReadTimeout))
		_, message, err := c.conn.ReadMessage()
		_ = c.conn.SetReadDeadline(time.Time{})
		if err != nil {
			return
		}
		var packet MaxPacket
		if json.Unmarshal(message, &packet) != nil {
			continue
		}
		if ch, ok := c.pending.LoadAndDelete(int64(packet.Seq)); ok {
			ch.(chan MaxPacket) <- packet
		} else if c.onEvent != nil {
			c.onEvent(packet)
		}
	}
}

func (c *MaxClient) invoke(opcode int, payload map[string]interface{}) (*MaxPacket, error) {
	seq := c.seq.Add(1)
	req := map[string]interface{}{"ver": RPC_VERSION, "cmd": 0, "seq": seq, "opcode": opcode, "payload": payload}
	data, _ := json.Marshal(req)
	ch := make(chan MaxPacket, 1)
	c.pending.Store(seq, ch)
	defer c.pending.Delete(seq)
	c.mu.Lock()
	err := c.conn.WriteMessage(websocket.TextMessage, data)
	c.mu.Unlock()
	if err != nil {
		return nil, err
	}
	select {
	case resp := <-ch:
		return &resp, nil
	case <-time.After(30 * time.Second):
		return nil, fmt.Errorf("timeout")
	}
}

func (c *MaxClient) LoginByToken(token string) error {
	c.invoke(6, map[string]interface{}{
		"userAgent": map[string]interface{}{
			"deviceType": "WEB", "locale": "ru_RU", "osVersion": "macOS",
			"deviceName": "vkmax Go", "appVersion": "25.9.15",
			"screen": "956x1470 2.0x", "timezone": "Asia/Vladivostok",
		},
		"deviceId": c.deviceID,
	})
	resp, err := c.invoke(19, map[string]interface{}{
		"interactive": true, "token": token, "chatsSync": 0,
		"contactsSync": 0, "presenceSync": 0, "draftsSync": 0, "chatsCount": 40,
	})
	if err != nil {
		return err
	}
	var payload map[string]interface{}
	json.Unmarshal(resp.Payload, &payload)
	if _, ok := payload["error"]; ok {
		return fmt.Errorf("login failed: %v", payload["error"])
	}
	c.loggedIn = true
	utils.SafeGo("oneme.wkeep", func() { c.keepalive() })
	// The count, never the people. Names and phone numbers are the personal
	// data of people who never asked to be in someone else's log window, and
	// this package's stdout is captured by the app's Logs screen - so a dump
	// here would put a contact list on screen every time the user connects.
	// Diagnosing a sync problem needs the number and the ids, not identities.
	if n := len(c.getUserMap(resp)); n > 0 {
		utils.Debugf("[MAX] contacts synced: %d", n)
	}
	return nil
}

func (c *MaxClient) getUserMap(resp *MaxPacket) map[int64]UserInfo {
	userMap := make(map[int64]UserInfo)
	var payload map[string]interface{}
	if json.Unmarshal(resp.Payload, &payload) != nil {
		return userMap
	}
	if contacts, ok := payload["contacts"].([]interface{}); ok {
		for _, contact := range contacts {
			cm := contact.(map[string]interface{})
			id := int64(cm["id"].(float64))
			u := UserInfo{ID: id}
			if phone, ok := cm["phone"].(float64); ok {
				u.Phone = int64(phone)
			}
			if names, ok := cm["names"].([]interface{}); ok {
				for _, name := range names {
					n := name.(map[string]interface{})
					if n["type"] == "ONEME" {
						if v, ok := n["firstName"].(string); ok {
							u.FirstName = v
						}
						if v, ok := n["lastName"].(string); ok {
							u.LastName = v
						}
						if v, ok := n["name"].(string); ok {
							u.FullName = v
						}
					}
				}
			}
			userMap[id] = u
		}
	}
	return userMap
}

func (c *MaxClient) keepalive() {
	defer func() {
		if r := recover(); r != nil {
			logError("recovered in MaxClient.keepalive: %v", r)
		}
	}()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		select {
		case <-c.keepaliveStop:
			return
		default:
			if c.loggedIn {
				c.invoke(1, map[string]interface{}{"interactive": false})
			}
		}
	}
}
