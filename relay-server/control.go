package main

import (
	"log"
	"net"
	"time"
)

const (
	heartbeatInterval = 15 * time.Second
	heartbeatTimeout  = 45 * time.Second
	dataConnTimeout   = 10 * time.Second
)

// handleControlConnection handles a new TLS connection from a mod client.
// It reads the first message to determine if this is an AUTH or CONN_READY.
func handleControlConnection(conn net.Conn, userStore *UserStore, sessionMgr *SessionManager, domain string) {
	// Read the first message
	env, err := ReadMessage(conn)
	if err != nil {
		log.Printf("[control] failed to read first message: %v", err)
		conn.Close()
		return
	}

	switch env.Type {
	case MsgAuth:
		handleAuth(conn, env, userStore, sessionMgr, domain)
	case MsgConnReady:
		handleConnReady(conn, env, sessionMgr)
	default:
		log.Printf("[control] unexpected first message type: %s", env.Type)
		conn.Close()
	}
}

// handleAuth processes an AUTH message and creates a session.
func handleAuth(conn net.Conn, env *Envelope, userStore *UserStore, sessionMgr *SessionManager, domain string) {
	auth, err := ParsePayload[AuthPayload](env)
	if err != nil {
		log.Printf("[auth] failed to parse auth payload: %v", err)
		WriteMessage(conn, MsgAuthErr, &AuthErrPayload{Reason: "invalid payload"})
		time.Sleep(500 * time.Millisecond)
		conn.Close()
		return
	}

	user, err := userStore.Authenticate(auth.UserID, auth.Token)
	if err != nil {
		log.Printf("[auth] auth failed for user %s: %v", auth.UserID, err)
		WriteMessage(conn, MsgAuthErr, &AuthErrPayload{Reason: "authentication failed"})
		time.Sleep(500 * time.Millisecond) // Give client time to read before TCP FIN
		conn.Close()
		return
	}

	// Build full domain for this user
	fullDomain := user.Subdomain + "." + domain
	log.Printf("[auth] user %s authenticated, domain: %s", user.UserID, fullDomain)

	// Create session (this kicks any existing session for the user)
	sess := sessionMgr.CreateSession(user.UserID, user.Subdomain, fullDomain, conn)

	// Send AUTH_OK
	if err := WriteMessage(conn, MsgAuthOk, &AuthOkPayload{Domain: fullDomain}); err != nil {
		log.Printf("[auth] failed to send auth_ok: %v", err)
		sessionMgr.RemoveSession(user.UserID)
		conn.Close()
		return
	}

	// Start heartbeat loop on control channel
	go controlLoop(sess, sessionMgr)
}

// controlLoop manages the control channel: heartbeat and message reading.
func controlLoop(sess *Session, sessionMgr *SessionManager) {
	defer sessionMgr.RemoveSession(sess.UserID)

	// Heartbeat ticker
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	// Read messages in a goroutine
	msgCh := make(chan *Envelope, 8)
	errCh := make(chan error, 1)
	go func() {
		for {
			env, err := ReadMessage(sess.controlConn)
			if err != nil {
				errCh <- err
				return
			}
			msgCh <- env
		}
	}()

	lastPong := time.Now()

	for {
		select {
		case <-ticker.C:
			// Send ping
			if err := WriteMessage(sess.controlConn, MsgPing, nil); err != nil {
				log.Printf("[heartbeat] failed to send ping to %s: %v", sess.UserID, err)
				return
			}
			// Check if we've timed out waiting for pong
			if time.Since(lastPong) > heartbeatTimeout {
				log.Printf("[heartbeat] timeout for user %s", sess.UserID)
				return
			}

		case env := <-msgCh:
			switch env.Type {
			case MsgPong:
				lastPong = time.Now()
			default:
				log.Printf("[control] unexpected message type from %s: %s", sess.UserID, env.Type)
			}

		case err := <-errCh:
			log.Printf("[control] connection error for user %s: %v", sess.UserID, err)
			return
		}
	}
}

// handleConnReady processes a CONN_READY message on a new data channel connection.
func handleConnReady(conn net.Conn, env *Envelope, sessionMgr *SessionManager) {
	ready, err := ParsePayload[ConnReadyPayload](env)
	if err != nil {
		log.Printf("[data] failed to parse conn_ready payload: %v", err)
		conn.Close()
		return
	}

	// Find the session that has this pending conn_id
	// We need to search all sessions since the data channel is a new connection
	// without prior auth context
	found := false
	sessionMgr.mu.RLock()
	for _, sess := range sessionMgr.sessions {
		sess.mu.Lock()
		_, exists := sess.pendingConns[ready.ConnID]
		sess.mu.Unlock()
		if exists {
			if err := sess.ResolvePendingConn(ready.ConnID, conn); err != nil {
				log.Printf("[data] failed to resolve conn %s: %v", ready.ConnID, err)
				conn.Close()
			} else {
				log.Printf("[data] data channel established for conn_id %s", ready.ConnID)
			}
			found = true
			break
		}
	}
	sessionMgr.mu.RUnlock()

	if !found {
		log.Printf("[data] no pending conn found for conn_id: %s", ready.ConnID)
		conn.Close()
	}
}
