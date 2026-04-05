package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"
)

type UserInfo struct {
	UserID    string    `json:"user_id"`
	Token     string    `json:"token"`
	Subdomain string    `json:"subdomain"` // e.g. "alice" → alice.mc.mydomain.com
	CreatedAt time.Time `json:"created_at"`
}

type UserStore struct {
	mu    sync.RWMutex
	users map[string]*UserInfo // user_id → UserInfo
}

func NewUserStore() *UserStore {
	return &UserStore{
		users: make(map[string]*UserInfo),
	}
}

func (s *UserStore) LoadFromFile(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read users file: %w", err)
	}
	var userList []UserInfo
	if err := json.Unmarshal(data, &userList); err != nil {
		return fmt.Errorf("parse users file: %w", err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.users = make(map[string]*UserInfo, len(userList))
	for i := range userList {
		s.users[userList[i].UserID] = &userList[i]
	}
	return nil
}

// Authenticate validates user_id + token. Returns UserInfo on success.
func (s *UserStore) Authenticate(userID, token string) (*UserInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	user, ok := s.users[userID]
	if !ok {
		return nil, fmt.Errorf("unknown user: %s", userID)
	}
	// Constant-time comparison to prevent timing attacks
	if subtle.ConstantTimeCompare([]byte(user.Token), []byte(token)) != 1 {
		return nil, fmt.Errorf("invalid token for user: %s", userID)
	}
	return user, nil
}

// UpdateUser modifies an existing user's subdomain or token.
func (s *UserStore) UpdateUser(userID, subdomain, token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	user, ok := s.users[userID]
	if !ok {
		return fmt.Errorf("unknown user: %s", userID)
	}
	
	user.Subdomain = subdomain
	if token != "" {
		user.Token = token
	}
	return nil
}

// GetUserBySubdomain finds a user by their subdomain prefix.
func (s *UserStore) GetUserBySubdomain(subdomain string) *UserInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, u := range s.users {
		if u.Subdomain == subdomain {
			return u
		}
	}
	return nil
}

// SaveToFile writes the current store to the specified path.
// The caller should hold the lock if they care about consistency, but
// we do a quick snapshot internally anyway just in case to avoid panic.
func (s *UserStore) SaveToFile(path string) error {
	s.mu.RLock()
	var userList []UserInfo
	for _, u := range s.users {
		userList = append(userList, *u)
	}
	s.mu.RUnlock()

	data, err := json.MarshalIndent(userList, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal users: %w", err)
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		return fmt.Errorf("write users file: %w", err)
	}
	return nil
}
