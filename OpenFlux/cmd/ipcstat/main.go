// Reads one Status frame off the core's IPC socket and prints it.
//
// The Windows and Android clients read their traffic counters from this
// stream, and a classic profile used to get no socket at all, so the numbers
// on screen stayed at zero. Nothing in the CLI exercises that path, so this
// is how the fix gets checked against a running core:
//
//	openflux-ipcstat /path/to/core.sock
//
// Exit status is 0 when a Status frame arrives, 1 on timeout or error.
package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"time"

	"openflux/transport/ipc"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: openflux-ipcstat <socket>")
		os.Exit(2)
	}
	conn, err := net.DialTimeout("unix", os.Args[1], 10*time.Second)
	if err != nil {
		fmt.Fprintln(os.Stderr, "dial:", err)
		os.Exit(1)
	}
	defer conn.Close()

	// The core sends a status every second. Two frames is enough to see the
	// counters move rather than just that one frame arrived.
	for i := 0; i < 2; i++ {
		_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
		var length [4]byte
		if _, err := io.ReadFull(conn, length[:]); err != nil {
			fmt.Fprintln(os.Stderr, "read length:", err)
			os.Exit(1)
		}
		n := binary.BigEndian.Uint32(length[:])
		if n == 0 || n > ipc.MaxFrameBytes {
			fmt.Fprintf(os.Stderr, "bad frame length %d\n", n)
			os.Exit(1)
		}
		frame := make([]byte, n)
		if _, err := io.ReadFull(conn, frame); err != nil {
			fmt.Fprintln(os.Stderr, "read frame:", err)
			os.Exit(1)
		}
		if ipc.MsgType(frame[0]) != ipc.MsgStatus {
			fmt.Printf("кадр %d: тип 0x%02x (не Status)\n", i, byte(frame[0]))
			continue
		}
		var st ipc.StatusPayload
		if err := json.Unmarshal(frame[1:], &st); err != nil {
			fmt.Fprintln(os.Stderr, "decode:", err)
			os.Exit(1)
		}
		fmt.Printf("кадр %d: running=%t connected=%t in=%d out=%d active=%q\n",
			i, st.Running, st.Connected, st.BytesIn, st.BytesOut, st.Active)
	}
}
