package tunnel

import (
	"math/rand"
	"testing"
	"time"
)

// How much work does the duplicate window cost per delivered packet? The scan
// is linear over dedupeCapacity entries and compares whole packet bytes, so
// this is the number that decides whether the guard is affordable at bulk
// transfer rates. Written before changing anything: a fix without a measured
// baseline is a guess.
func BenchmarkDedupeFullWindow(b *testing.B) {
	d := newDedupe()
	now := time.Now()
	// A realistic TCP data segment. Every packet here is distinct, which is
	// the common case and the one that must walk the entire window.
	seed := rand.New(rand.NewSource(1))
	pkts := make([][]byte, 512)
	for i := range pkts {
		p := make([]byte, 1400)
		binaryPutU32(p[0:4], seed.Uint32())
		binaryPutU32(p[4:8], seed.Uint32())
		binaryPutU32(p[8:12], uint32(i))
		pkts[i] = p
	}
	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		d.duplicate(pkts[i%len(pkts)], now)
	}
}

func binaryPutU32(b []byte, v uint32) {
	b[0], b[1], b[2], b[3] = byte(v>>24), byte(v>>16), byte(v>>8), byte(v)
}