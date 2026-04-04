package main

import (
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/google/uuid"
)

// Session represents an active tunnel for one user.
type Session struct {
	UserID    string
	Subdomain string
	Domain    string

	controlConn net.Conn
	mu          sync.Mutex

	// Pending player connections waiting for mod to open data channel
	pendingConns map[string]chan net.Conn // conn_id → channel to deliver data conn
}

func NewSession(userID, subdomain, domain string, controlConn net.Conn) *Session {
	return &Session{
		UserID:       userID,
		Subdomain:    subdomain,
		Domain:       domain,
		controlConn:  controlConn,
		pendingConns: make(map[string]chan net.Conn),
	}
}

// GenerateConnID creates a unique connection ID.
func (s *Session) GenerateConnID() string {
	return uuid.New().String()[:8]
}

// AddPendingConn registers a pending player connection.
// Returns a channel that will receive the data connection from the mod.
func (s *Session) AddPendingConn(connID string) chan net.Conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	ch := make(chan net.Conn, 1)
	s.pendingConns[connID] = ch
	return ch
}

// ResolvePendingConn delivers the mod's data connection for a conn_id.
func (s *Session) ResolvePendingConn(connID string, dataConn net.Conn) error {
	s.mu.Lock()
	ch, ok := s.pendingConns[connID]
	if ok {
		delete(s.pendingConns, connID)
	}
	s.mu.Unlock()

	if !ok {
		return fmt.Errorf("no pending connection for conn_id: %s", connID)
	}
	ch <- dataConn
	return nil
}

// SendNewConn sends a NEW_CONN message to the mod via the control channel.
func (s *Session) SendNewConn(connID string) error {
	return WriteMessage(s.controlConn, MsgNewConn, &NewConnPayload{ConnID: connID})
}

// Close cleans up the session.
func (s *Session) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.controlConn != nil {
		s.controlConn.Close()
	}
	// Close all pending connection channels
	for id, ch := range s.pendingConns {
		close(ch)
		delete(s.pendingConns, id)
	}
}

// --- Session Manager ---

type SessionManager struct {
	mu       sync.RWMutex
	sessions map[string]*Session // user_id → Session
}

func NewSessionManager() *SessionManager {
	return &SessionManager{
		sessions: make(map[string]*Session),
	}
}

// CreateSession creates a new session, closing any existing one for this user.
func (sm *SessionManager) CreateSession(userID, subdomain, domain string, controlConn net.Conn) *Session {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	// Kick existing session
	if old, ok := sm.sessions[userID]; ok {
		log.Printf("[session] kicking old session for user %s", userID)
		old.Close()
	}

	sess := NewSession(userID, subdomain, domain, controlConn)
	sm.sessions[userID] = sess
	log.Printf("[session] created session for user %s (%s)", userID, domain)
	return sess
}

// RemoveSession removes and closes a session.
func (sm *SessionManager) RemoveSession(userID string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if sess, ok := sm.sessions[userID]; ok {
		sess.Close()
		delete(sm.sessions, userID)
		log.Printf("[session] removed session for user %s", userID)
	}
}

// GetSessionBySubdomain finds a session by subdomain.
func (sm *SessionManager) GetSessionBySubdomain(subdomain string) *Session {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	for _, sess := range sm.sessions {
		if sess.Subdomain == subdomain {
			return sess
		}
	}
	return nil
}

// GetSession gets a session by user ID.
func (sm *SessionManager) GetSession(userID string) *Session {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.sessions[userID]
}

// WaitForDataConn waits for the mod to establish the data channel for a conn_id.
func WaitForDataConn(sess *Session, connID string, timeout time.Duration) (net.Conn, error) {
	ch := sess.AddPendingConn(connID)
	select {
	case conn := <-ch:
		if conn == nil {
			return nil, fmt.Errorf("session closed while waiting for data conn")
		}
		return conn, nil
	case <-time.After(timeout):
		sess.mu.Lock()
		delete(sess.pendingConns, connID)
		sess.mu.Unlock()
		return nil, fmt.Errorf("timeout waiting for data conn (conn_id: %s)", connID)
	}
}
