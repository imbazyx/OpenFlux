package oneme

import (
	"encoding/base64"
	"encoding/json"
	"fmt"

	"github.com/pierrec/lz4/v4"
)

// maxCallDetails bounds the decompressed call configuration. The real thing is
// a short JSON blob; the cap is a ceiling, not a target.
const maxCallDetails = 64 << 10

func decodeCallDetails(vcp string) (string, error) {
	if len(vcp) < 4 {
		return "", fmt.Errorf("vcp too short")
	}
	// size comes from the caller, so it is a number the peer chose. It used to
	// go straight into make and then slice without a bound check, which let a
	// malformed frame panic the read loop. A call config is a few hundred
	// bytes; anything claiming more is not a call config.
	var size int
	if _, err := fmt.Sscanf(vcp[:3], "%d", &size); err != nil {
		return "", fmt.Errorf("unreadable size prefix %q", vcp[:3])
	}
	if size <= 0 || size > maxCallDetails {
		return "", fmt.Errorf("call details size %d out of range", size)
	}
	decoded, err := base64.StdEncoding.DecodeString(vcp[4:])
	if err != nil {
		return "", err
	}
	decompressed := make([]byte, size)
	n, err := lz4.UncompressBlock(decoded, decompressed)
	if err != nil {
		return "", err
	}
	// lz4 reports what it wrote; a peer cannot make that exceed the buffer we
	// handed it, but trusting the return value over the slice length is how
	// this became a panic in the first place.
	if n < 0 || n > len(decompressed) {
		return "", fmt.Errorf("lz4 reported %d bytes into a %d byte buffer", n, len(decompressed))
	}
	return string(decompressed[:n]), nil
}

func craftEndpoint(convID, jsonConfig string) string {
	var config WebRTCConfig
	json.Unmarshal([]byte(jsonConfig), &config)
	baseURL := config.WebsocketEndpoint
	if len(baseURL) > 4 {
		baseURL = baseURL[:len(baseURL)-4]
	}
	userID := config.TurnUsername
	for i := len(config.TurnUsername) - 1; i >= 0; i-- {
		if config.TurnUsername[i] == ':' {
			userID = config.TurnUsername[i+1:]
			break
		}
	}
	return fmt.Sprintf("%s/ws2?userId=%s&entityType=USER&deviceIdx=0&conversationId=%s&token=%s&platform=WEB&appVersion=1.1&version=5&device=browser&capabilities=2A03F&clientType=ONE_ME&tgt=accept",
		baseURL, userID, convID, config.Token)
}
