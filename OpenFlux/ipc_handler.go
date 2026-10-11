package main

import (
	"sync"

	"openflux/transport/ipc"
	"openflux/transport/manager"
	"openflux/utils"
)

// coreIPCHandler is the app-facing side of the IPC bridge.
//
// It receives commands and cookies from the mobile app and forwards them
// to the manager. When the core needs fresh cookies (SmartCaptcha), the
// manager calls SetCaptchaNotifier, whose callback fires into IPC.
//
// The manager is nil on the classic single-transport path, which has no
// Manager to hand cookies to. That path still gets the status stream, so the
// app can show traffic, and a cookie offer arriving there is a protocol
// mistake rather than something to act on.
type coreIPCHandler struct {
	mu      sync.RWMutex
	manager *manager.Manager
}

// setManager is called once the Manager exists, which happens only on the
// Session path, after the IPC socket is already listening. Guarding the field
// instead of writing it unlocked avoids the read/write race between the
// session-startup goroutine and the server goroutine that calls OnCookies.
func (h *coreIPCHandler) setManager(m *manager.Manager) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.manager = m
}

func (h *coreIPCHandler) OnConnect()    { utils.Debugf("[IPC] app connected") }
func (h *coreIPCHandler) OnDisconnect() { utils.Debugf("[IPC] app disconnected") }

func (h *coreIPCHandler) OnCommand(p *ipc.CommandPayload) {
	utils.Debugf("[IPC] command action=%q params=%v", p.Action, p.Params)
	// No dynamic commands yet. Reserved for start/stop/restart from the UI.
}

func (h *coreIPCHandler) OnCookies(p *ipc.CookiesOfferPayload) {
	if p == nil || p.Transport == "" || len(p.Jar) == 0 {
		utils.Debugf("[IPC] empty cookies offer, ignoring")
		return
	}
	h.mu.RLock()
	m := h.manager
	h.mu.RUnlock()
	if m == nil {
		utils.Debugf("[IPC] cookies offer for %q on a profile without a manager, ignoring", p.Transport)
		return
	}
	if p.Remote {
		if err := h.manager.OfferCookies(p.Transport, p.Jar); err != nil {
			utils.Debugf("[IPC] offer cookies to exit for %q: %v", p.Transport, err)
			return
		}
		utils.Debugf("[IPC] sent %d cookies to exit for %q", len(p.Jar), p.Transport)
		return
	}
	if err := h.manager.AcceptCookies(p.Transport, p.Jar); err != nil {
		utils.Debugf("[IPC] apply cookies for %q: %v", p.Transport, err)
		return
	}
	utils.Debugf("[IPC] applied %d cookies for %q", len(p.Jar), p.Transport)
}
