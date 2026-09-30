package oneme

import (
	"crypto/rand"
	"errors"
	"fmt"
	"net/url"
	"time"

	"openflux/utils"
)

// The project's logger, not fmt.Printf.
//
// This package printed straight to stdout, and both clients capture the core's
// output and show it in the Logs screen - so every line was visible whether or
// not debug had been asked for, with no level and no way to turn it off. Going
// through utils puts these lines in the same stream and at the same levels as
// the rest of the core, and the [MAX] tag keeps them findable.
var vvv bool

// Bounds for the two signaling sockets. A call configuration arrives base64
// and LZ4-encoded and is well under a kilobyte; four megabytes leaves room for
// a large SDP with ICE candidates without being a memory knob the peer holds.
const (
	maxWSFrameBytes = 4 << 20
	wsReadTimeout   = 10 * time.Minute
)

func logDebug(format string, args ...interface{}) {
	if vvv {
		utils.Debugf("[MAX] "+format, args...)
	}
}

func logInfo(format string, args ...interface{}) {
	utils.Infof("[MAX] "+format, args...)
}

func logError(format string, args ...interface{}) {
	utils.Infof("[MAX] "+format, args...)
}

// redactedEndpoint drops the query string from a signaling URL.
//
// The URL carries the call token in ?token=, and that token is full access to
// the MAX account for as long as the call lasts. Logging the URL printed it in
// clear; gorilla's dial errors embed the same URL, so the error path leaked it
// as well. Scheme, host and path are what tell you which node failed.
func redactedEndpoint(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "<unparsable endpoint>"
	}
	return u.Scheme + "://" + u.Host + u.Path
}

// redactedError keeps a connection error useful without its URL. gorilla wraps
// the endpoint in *url.Error, so %v on it would print the token too.
func redactedError(err error) string {
	if err == nil {
		return ""
	}
	var uerr *url.Error
	if errors.As(err, &uerr) {
		return fmt.Sprintf("%s %s: %v", uerr.Op, redactedEndpoint(uerr.URL), uerr.Err)
	}
	return err.Error()
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func genUUID() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
