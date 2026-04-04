package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
)

// --- Minecraft Handshake Packet Parser ---
// Minecraft protocol: https://wiki.vg/Protocol#Handshake
//
// The first packet a Minecraft client sends is the Handshake:
//   [VarInt: Packet Length][VarInt: Packet ID = 0x00]
//   [VarInt: Protocol Version][String: Server Address][Unsigned Short: Server Port][VarInt: Next State]
//
// We read the Server Address to determine which subdomain to route to.

// readVarInt reads a Minecraft protocol VarInt from the reader.
func readVarInt(r io.Reader) (int, int, error) {
	var result int
	var numRead int
	buf := make([]byte, 1)
	for {
		if _, err := io.ReadFull(r, buf); err != nil {
			return 0, 0, err
		}
		numRead++
		value := int(buf[0] & 0x7F)
		result |= value << (7 * (numRead - 1))
		if buf[0]&0x80 == 0 {
			break
		}
		if numRead > 5 {
			return 0, 0, fmt.Errorf("VarInt too long")
		}
	}
	return result, numRead, nil
}

// readMCString reads a Minecraft protocol String (VarInt length + UTF-8 data).
func readMCString(r io.Reader) (string, error) {
	length, _, err := readVarInt(r)
	if err != nil {
		return "", fmt.Errorf("read string length: %w", err)
	}
	if length > 255 {
		return "", fmt.Errorf("string too long: %d", length)
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", fmt.Errorf("read string data: %w", err)
	}
	return string(buf), nil
}

// parseMinecraftHandshake reads the initial bytes of a Minecraft connection and
// returns the server_address from the handshake packet, plus all the raw bytes
// read (so they can be forwarded to the actual server).
func parseMinecraftHandshake(conn net.Conn) (serverAddress string, rawBytes []byte, err error) {
	// We need to buffer everything we read so we can forward it later
	var buf []byte
	r := &bufferingReader{conn: conn, buf: &buf}

	// Read packet length (VarInt)
	packetLen, _, err := readVarInt(r)
	if err != nil {
		return "", buf, fmt.Errorf("read packet length: %w", err)
	}
	if packetLen > 1024 {
		return "", buf, fmt.Errorf("handshake packet too large: %d", packetLen)
	}

	// Read packet ID (VarInt, should be 0x00 for Handshake)
	packetID, _, err := readVarInt(r)
	if err != nil {
		return "", buf, fmt.Errorf("read packet id: %w", err)
	}
	if packetID != 0 {
		return "", buf, fmt.Errorf("unexpected packet id: %d (expected 0)", packetID)
	}

	// Read protocol version (VarInt) - we don't need this but must consume it
	_, _, err = readVarInt(r)
	if err != nil {
		return "", buf, fmt.Errorf("read protocol version: %w", err)
	}

	// Read server address (String)
	serverAddress, err = readMCString(r)
	if err != nil {
		return "", buf, fmt.Errorf("read server address: %w", err)
	}

	// Read server port (Unsigned Short) - consume it
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(r, portBuf); err != nil {
		return serverAddress, buf, fmt.Errorf("read server port: %w", err)
	}

	// Read next state (VarInt) - consume it
	_, _, err = readVarInt(r)
	if err != nil {
		return serverAddress, buf, fmt.Errorf("read next state: %w", err)
	}

	return serverAddress, buf, nil
}

// bufferingReader wraps a connection and records all bytes read.
type bufferingReader struct {
	conn net.Conn
	buf  *[]byte
}

func (r *bufferingReader) Read(p []byte) (int, error) {
	n, err := r.conn.Read(p)
	if n > 0 {
		*r.buf = append(*r.buf, p[:n]...)
	}
	return n, err
}

// extractSubdomain gets the subdomain from a server address.
// e.g. "alice.mc.mydomain.com" with domain "mc.mydomain.com" → "alice"
func extractSubdomain(serverAddress, domain string) (string, error) {
	// Remove FML marker if present (Forge adds \x00FML\x00 or similar)
	addr := strings.Split(serverAddress, "\x00")[0]
	addr = strings.TrimSuffix(addr, ".")

	if !strings.HasSuffix(strings.ToLower(addr), "."+strings.ToLower(domain)) {
		return "", fmt.Errorf("address %q does not match domain %q", addr, domain)
	}

	// Remove the domain suffix to get subdomain
	sub := addr[:len(addr)-len(domain)-1]
	sub = strings.ToLower(sub)
	if sub == "" {
		return "", fmt.Errorf("empty subdomain in %q", addr)
	}
	return sub, nil
}

// --- Public TCP Listener (port 25565) ---

// startPublicListener listens on the public Minecraft port and routes
// connections based on the Minecraft handshake server_address field.
func startPublicListener(port int, domain string, sessionMgr *SessionManager) error {
	addr := fmt.Sprintf(":%d", port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", addr, err)
	}
	log.Printf("[public] listening on %s for Minecraft connections", addr)

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				log.Printf("[public] accept error: %v", err)
				continue
			}
			go handlePlayerConnection(conn, domain, sessionMgr)
		}
	}()

	return nil
}

// handlePlayerConnection reads the Minecraft handshake, resolves the subdomain
// to a user session, and bridges the player to the mod via the relay.
func handlePlayerConnection(playerConn net.Conn, domain string, sessionMgr *SessionManager) {
	remoteAddr := playerConn.RemoteAddr().String()
	log.Printf("[public] new player connection from %s", remoteAddr)

	// Parse the Minecraft handshake to get server_address
	serverAddress, handshakeBytes, err := parseMinecraftHandshake(playerConn)
	if err != nil {
		log.Printf("[public] failed to parse handshake from %s: %v", remoteAddr, err)
		playerConn.Close()
		return
	}
	log.Printf("[public] player %s connecting to %s", remoteAddr, serverAddress)

	// Extract subdomain
	subdomain, err := extractSubdomain(serverAddress, domain)
	if err != nil {
		log.Printf("[public] invalid subdomain from %s: %v", remoteAddr, err)
		playerConn.Close()
		return
	}

	// Find session for this subdomain
	sess := sessionMgr.GetSessionBySubdomain(subdomain)
	if sess == nil {
		log.Printf("[public] no active session for subdomain %s", subdomain)
		playerConn.Close()
		return
	}

	// Generate conn_id and notify the mod
	connID := sess.GenerateConnID()
	log.Printf("[public] routing player %s → user %s (conn_id: %s)", remoteAddr, sess.UserID, connID)

	// Send NEW_CONN to mod and wait for data channel
	if err := sess.SendNewConn(connID); err != nil {
		log.Printf("[public] failed to send new_conn to mod: %v", err)
		playerConn.Close()
		return
	}

	dataConn, err := WaitForDataConn(sess, connID, dataConnTimeout)
	if err != nil {
		log.Printf("[public] %v", err)
		playerConn.Close()
		return
	}

	log.Printf("[public] bridging player %s ↔ mod (conn_id: %s)", remoteAddr, connID)

	// First, forward the handshake bytes we already read
	if _, err := dataConn.Write(handshakeBytes); err != nil {
		log.Printf("[public] failed to forward handshake: %v", err)
		playerConn.Close()
		dataConn.Close()
		return
	}

	// Bridge the two connections
	go bridgeConnections(playerConn, dataConn)
}

// bridgeConnections pipes data bidirectionally between two connections.
func bridgeConnections(conn1, conn2 net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	// conn1 → conn2
	go func() {
		defer wg.Done()
		io.Copy(conn2, conn1)
		// Signal the other direction to stop by closing write half
		if tc, ok := conn2.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()

	// conn2 → conn1
	go func() {
		defer wg.Done()
		io.Copy(conn1, conn2)
		if tc, ok := conn1.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()

	wg.Wait()
	conn1.Close()
	conn2.Close()
}
