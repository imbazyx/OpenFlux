package transport

import (
	"bytes"
	"fmt"
	"io"

	"github.com/pierrec/lz4/v4"
)

const (
	MinCompressSize   = 200
	CompressionMarker = 0x1F
)

type CompressedTransport struct {
	Transport
}

func NewCompressedTransport(inner Transport) Transport {
	return &CompressedTransport{Transport: inner}
}

func (c *CompressedTransport) Send(data []byte) error {
	compressed := compress(data)
	return c.Transport.Send(compressed)
}

func (c *CompressedTransport) Receive(callback func([]byte)) {
	c.Transport.Receive(func(data []byte) {
		decompressed, err := decompress(data)
		if err != nil {
			callback(data) // fallback
			return
		}
		callback(decompressed)
	})
}

func compress(data []byte) []byte {
	if len(data) <= MinCompressSize {
		out := make([]byte, 1, len(data)+1)
		out[0] = 0x00
		out = append(out, data...)
		return out
	}

	var buf bytes.Buffer
	buf.WriteByte(CompressionMarker)

	w := lz4.NewWriter(&buf)
	w.Write(data)
	w.Close()

	if buf.Len() >= len(data)+1 {
		out := make([]byte, 1, len(data)+1)
		out[0] = 0x00
		out = append(out, data...)
		return out
	}

	return buf.Bytes()
}

// maxDecompressed bounds what a single frame may expand to.
//
// LZ4 on bytes taken off the wire has no natural bound: a few kilobytes can
// expand to hundreds of megabytes, and io.ReadAll with no limit grows until
// the process is killed. This runs on every received frame in the legacy
// codec, so anyone who can put bytes on the carrier could end the session -
// or the phone - with one message.
const maxDecompressed = 1 << 20

func decompress(data []byte) ([]byte, error) {
	if len(data) < 1 {
		return data, nil
	}

	if data[0] == 0x00 {
		return data[1:], nil
	}

	r := lz4.NewReader(bytes.NewReader(data[1:]))
	out, err := io.ReadAll(io.LimitReader(r, maxDecompressed+1))
	if err != nil {
		return nil, err
	}
	if len(out) > maxDecompressed {
		return nil, fmt.Errorf("compressed: frame expands past %d bytes", maxDecompressed)
	}
	return out, nil
}
