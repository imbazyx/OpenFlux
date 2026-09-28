package transport

import (
	"sync"
	"testing"
	"time"
)

// TestBaseTransportRestartDone pins the lifecycle the watchdogs depend on:
// a Stop() must close the channel Done() hands out, and a following Start()
// must hand out a NEW, open one.
//
// Measured: this test PASSES on the unsynchronised code, because Start/Stop
// and Done() never ran concurrently in it. The race it fails on is caught by
// TestBaseTransportDoneRaces under -race. Keep both - the assertions here are
// the contract, the other test is the detector.
func TestBaseTransportRestartDone(t *testing.T) {
	b := NewBaseTransport(DefaultConfig())
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	first := b.Done()
	if first == nil {
		t.Fatal("Done() returned nil after Start")
	}
	if err := b.Stop(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-first:
	default:
		t.Fatal("Stop() did not close the channel Done() had returned")
	}

	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	second := b.Done()
	select {
	case <-second:
		t.Fatal("Start() returned an already-closed Done(); the watchdog would die instantly")
	default:
	}
	if second == first {
		t.Fatal("Start() reused the closed channel from the previous run")
	}
	if err := b.Stop(); err != nil {
		t.Fatal(err)
	}
}

// TestBaseTransportDoneRaces hammers Done() against Start/Stop. It asserts
// nothing on purpose: under -race this is a race detector, and its job is to
// fire on the old unsynchronised code and stay quiet on the fixed one.
func TestBaseTransportDoneRaces(t *testing.T) {
	b := NewBaseTransport(DefaultConfig())
	var wg sync.WaitGroup
	stop := make(chan struct{})

	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
					select {
					case <-b.Done():
					case <-time.After(time.Millisecond):
					}
				}
			}
		}()
	}

	for i := 0; i < 200; i++ {
		_ = b.Start()
		_ = b.Stop()
	}
	close(stop)
	wg.Wait()
}
