package ipc

import (
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"openflux/utils"
)

// Handler receives decoded messages from the connected client.
type Handler interface {
	OnCommand(p *CommandPayload)
	OnCookies(p *CookiesOfferPayload)
	OnConnect()
	OnDisconnect()
}

// Server is a single-client Unix domain socket server.
type Server struct {
	path    string
	handler Handler

	mu       sync.Mutex
	listener net.Listener
	conn     net.Conn
	writeMu  sync.Mutex
	closed   bool
}

func NewServer(path string, h Handler) *Server {
	return &Server{path: path, handler: h}
}

// Listen removes a stale socket file if present and binds a fresh one.
func (s *Server) Listen() error {
	if s.path == "" {
		return errors.New("ipc: empty path")
	}
	if _, err := os.Stat(s.path); err == nil {
		_ = os.Remove(s.path)
	}
	ln, err := net.Listen("unix", s.path)
	if err != nil {
		return err
	}
	// Go never restricts a socket it binds: connect(2) needs write permission,
	// and the default 0777 & ~umask leaves 0755 here, so the file is readable
	// and, under any permissive umask or a group-writable parent directory,
	// connectable by anyone who can reach it. Nothing in the protocol
	// authenticates a peer - any connection that lands here is "the app" - and
	// what it gets is the cookie jar and the right to push a new one, so the
	// socket belongs to the user that bound it and nobody else.
	//
	// ponytail: no SO_PEERCRED check. 0600 on the socket already means only the
	// owner can connect, which is the whole of the threat. Add the credential
	// check if the socket ever has to live in a directory shared by several uids.
	if err := os.Chmod(s.path, 0600); err != nil {
		_ = ln.Close()
		return fmt.Errorf("ipc: restrict %s: %w", s.path, err)
	}
	s.mu.Lock()
	s.listener = ln
	s.mu.Unlock()

	utils.Debugf("[IPC] listening on %s", s.path)
	// SafeGo: acceptLoop reaches s.handler.OnConnect() directly, and handler code
	// is exactly the panic surface the per-connection wrap below guards.
	utils.SafeGo("ipc.accept", s.acceptLoop)
	return nil
}

// acceptLoop sleeps a little between unexpected accept errors.
//
// Go's internal/poll already retries EINTR, ECONNABORTED and EAGAIN, so
// anything arriving here is a real one: EMFILE, ENFILE, ENOBUFS, ENFILE. At
// the descriptor limit Accept returns immediately, every time, so a bare
// continue is a tight loop - and one Debugf per iteration floods the log at
// exactly the moment the process is already out of descriptors. Bounded by the
// 0600 mode on the socket, so this is robustness, not an attack path.
const acceptErrorBackoff = 50 * time.Millisecond

func (s *Server) acceptLoop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			s.mu.Lock()
			closed := s.closed
			s.mu.Unlock()
			if closed {
				return
			}
			utils.Debugf("[IPC] accept: %v", err)
			time.Sleep(acceptErrorBackoff)
			continue
		}
		s.mu.Lock()
		if s.conn != nil {
			_ = s.conn.Close()
		}
		s.conn = conn
		s.mu.Unlock()
		if s.handler != nil {
			s.handler.OnConnect()
		}
		// SafeGo: `serve` reaches s.handler.OnCommand/OnCookies with a payload
		// parsed off the wire, and the per-connection entry point had no
		// recover of its own.
		utils.SafeGo("ipc.conn", func() { s.serve(conn) })
	}
}

func (s *Server) serve(conn net.Conn) {
	defer func() {
		s.mu.Lock()
		if s.conn == conn {
			s.conn = nil
		}
		s.mu.Unlock()
		_ = conn.Close()
		if s.handler != nil {
			s.handler.OnDisconnect()
		}
	}()

	for {
		typ, payload, err := ReadFrame(conn)
		if err != nil {
			return
		}
		switch typ {
		case MsgCommand:
			var p CommandPayload
			if err := DecodeJSON(payload, &p); err != nil {
				utils.Debugf("[IPC] bad Command: %v", err)
				continue
			}
			if s.handler != nil {
				s.handler.OnCommand(&p)
			}
		case MsgCookiesOffer:
			var p CookiesOfferPayload
			if err := DecodeJSON(payload, &p); err != nil {
				utils.Debugf("[IPC] bad CookiesOffer: %v", err)
				continue
			}
			if s.handler != nil {
				s.handler.OnCookies(&p)
			}
		default:
			utils.Debugf("[IPC] unexpected type 0x%02x from app", typ)
		}
	}
}

// Send writes a frame to the connected client. If no client is connected,
// the frame is dropped.
func (s *Server) Send(typ MsgType, payload interface{}) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	s.mu.Lock()
	conn := s.conn
	s.mu.Unlock()
	if conn == nil {
		return errors.New("ipc: no client connected")
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return WriteFrame(conn, typ, payload)
}

func (s *Server) SendCookiesRequest(p *CookiesRequestPayload) error {
	return s.Send(MsgCookiesRequest, p)
}

func (s *Server) SendStatus(p *StatusPayload) error {
	return s.Send(MsgStatus, p)
}

func (s *Server) SendLog(line string) error {
	return s.Send(MsgLog, &LogPayload{Line: line})
}

// Close stops the server and removes the socket file.
func (s *Server) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	ln := s.listener
	conn := s.conn
	s.mu.Unlock()
	if ln != nil {
		_ = ln.Close()
	}
	if conn != nil {
		_ = conn.Close()
	}
	if s.path != "" {
		_ = os.Remove(s.path)
	}
	return nil
}
