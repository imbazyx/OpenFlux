package utils

import (
	"bytes"
	"log"
	"strings"
	"sync"
	"testing"
	"time"
)

// safeWriter makes a bytes.Buffer usable from the panicking goroutine while the
// test polls it.
type safeWriter struct {
	w  *bytes.Buffer
	mu *sync.Mutex
}

func (s safeWriter) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.w.Write(p)
}

// A recovered panic on a node has to be visible at the default log level.
//
// SafeGo logged through Debugf, which is gated on the debug level, and the exit
// nodes run without --debug. The recover worked and the line vanished: the
// goroutine died, whatever it was serving simply stopped, and the journal -
// the only thing an admin of a node that runs for months has - said nothing.
// Silently broken is harder to diagnose than loudly down.
func TestSafeGoReportsThePanicAndTheStack(t *testing.T) {
	SetLevel(LevelOff)
	defer SetLevel(LevelOff)

	// Infof goes to the standard logger, not through logSink, and on a node
	// that standard logger writes to stderr where systemd collects it. Capture
	// that, because that is the path a panic actually takes in production.
	// The buffer needs a mutex: the panicking goroutine writes to it while
	// this one polls it, and bytes.Buffer is not safe for that.
	var mu sync.Mutex
	var buf bytes.Buffer
	restore := log.Writer()
	log.SetOutput(safeWriter{w: &buf, mu: &mu})
	defer log.SetOutput(restore)

	// The panic is recovered in SafeGo's own deferred func, which runs AFTER
	// fn's own defers - so waiting for fn to finish proves nothing, and did
	// fail under -race for exactly that reason. Wait for the line, which is
	// what is being asserted, with a bound so a missing log fails rather than
	// hangs.
	SafeGo("test.worker", func() { panic("deliberate") })
	deadline := time.Now().Add(2 * time.Second)
	for {
		mu.Lock()
		seen := strings.Contains(buf.String(), "PANIC")
		mu.Unlock()
		if seen || time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Millisecond)
	}

	mu.Lock()
	text := buf.String()
	mu.Unlock()
	if !strings.Contains(text, "PANIC") {
		t.Fatalf("no panic reported at LevelOff; got:\n%s", text)
	}
	if !strings.Contains(text, "deliberate") {
		t.Fatalf("the panic value is missing; got:\n%s", text)
	}
	if !strings.Contains(text, "goroutine") {
		t.Fatalf("no stack in the report, so the bug cannot be located; got:\n%s", text)
	}
	if !strings.Contains(text, "test.worker") {
		t.Fatalf("the worker name is missing; got:\n%s", text)
	}
}

// The point of SafeGo: a panicking worker must not take the process with it.
func TestSafeGoKeepsTheProcessAlive(t *testing.T) {
	SetLevel(LevelOff)
	defer SetLevel(LevelOff)

	after := make(chan struct{})
	SafeGo("boom", func() { panic("deliberate") })
	SafeGo("after", func() { close(after) })
	<-after // reaching here at all is the assertion
}
