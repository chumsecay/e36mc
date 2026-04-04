package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
)

// --- Message Types ---
const (
	MsgAuth      = "auth"
	MsgAuthOk    = "auth_ok"
	MsgAuthErr   = "auth_err"
	MsgNewConn   = "new_conn"
	MsgConnReady = "conn_ready"
	MsgPing      = "ping"
	MsgPong      = "pong"
)

// Envelope wraps every message on the wire.
type Envelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// --- Payload structs ---

type AuthPayload struct {
	UserID string `json:"user_id"`
	Token  string `json:"token"`
}

type AuthOkPayload struct {
	Domain string `json:"domain"`
}

type AuthErrPayload struct {
	Reason string `json:"reason"`
}

type NewConnPayload struct {
	ConnID string `json:"conn_id"`
}

type ConnReadyPayload struct {
	ConnID string `json:"conn_id"`
}

// --- Wire helpers: length-prefixed JSON ---

const maxMessageSize = 1 << 16 // 64 KB

// WriteMessage writes a length-prefixed JSON envelope to the connection.
func WriteMessage(conn net.Conn, msgType string, payload interface{}) error {
	var raw json.RawMessage
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return fmt.Errorf("marshal payload: %w", err)
		}
		raw = b
	}
	env := Envelope{Type: msgType, Payload: raw}
	data, err := json.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	if len(data) > maxMessageSize {
		return fmt.Errorf("message too large: %d bytes", len(data))
	}

	// Write 4-byte big-endian length prefix
	lenBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(lenBuf, uint32(len(data)))
	if _, err := conn.Write(lenBuf); err != nil {
		return fmt.Errorf("write length: %w", err)
	}
	if _, err := conn.Write(data); err != nil {
		return fmt.Errorf("write data: %w", err)
	}
	return nil
}

// ReadMessage reads a length-prefixed JSON envelope from the connection.
func ReadMessage(conn net.Conn) (*Envelope, error) {
	// Read 4-byte length prefix
	lenBuf := make([]byte, 4)
	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		return nil, fmt.Errorf("read length: %w", err)
	}
	msgLen := binary.BigEndian.Uint32(lenBuf)
	if msgLen > uint32(maxMessageSize) {
		return nil, fmt.Errorf("message too large: %d bytes", msgLen)
	}

	// Read the JSON body
	data := make([]byte, msgLen)
	if _, err := io.ReadFull(conn, data); err != nil {
		return nil, fmt.Errorf("read data: %w", err)
	}

	var env Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, fmt.Errorf("unmarshal envelope: %w", err)
	}
	return &env, nil
}

// ParsePayload unmarshals the payload of an envelope into the given struct.
func ParsePayload[T any](env *Envelope) (*T, error) {
	var t T
	if err := json.Unmarshal(env.Payload, &t); err != nil {
		return nil, fmt.Errorf("parse payload: %w", err)
	}
	return &t, nil
}
