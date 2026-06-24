package main

import (
	"crypto/subtle"
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"strings"
	"time"
)

//go:embed web/*
var webFS embed.FS

type WebServer struct {
	cfg        *Config
	configPath string
	userStore  *UserStore
	sessionMgr *SessionManager
}

func NewWebServer(cfg *Config, configPath string, userStore *UserStore, sessionMgr *SessionManager) *WebServer {
	return &WebServer{
		cfg:        cfg,
		configPath: configPath,
		userStore:  userStore,
		sessionMgr: sessionMgr,
	}
}

type UserStatus struct {
	UserInfo
	IsOnline    bool      `json:"is_online"`
	ConnectedAt time.Time `json:"connected_at"`
	TxSpeed     uint64    `json:"tx_speed"` // bytes/sec to player
	RxSpeed  uint64 `json:"rx_speed"` // bytes/sec from player
}

// Middleware to check admin token
func (ws *WebServer) authMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := r.Header.Get("Authorization")
		if strings.HasPrefix(token, "Bearer ") {
			token = strings.TrimPrefix(token, "Bearer ")
		}

		if token == "" || len(token) != len(ws.cfg.AdminToken) || subtle.ConstantTimeCompare([]byte(token), []byte(ws.cfg.AdminToken)) != 1 {
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func (ws *WebServer) handleGetUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	ws.userStore.mu.RLock()
	userSnapshot := make([]UserInfo, 0, len(ws.userStore.users))
	for _, u := range ws.userStore.users {
		userSnapshot = append(userSnapshot, *u)
	}
	ws.userStore.mu.RUnlock()

	var users []UserStatus
	for _, u := range userSnapshot {
		status := UserStatus{
			UserInfo: u,
		}

		sess := ws.sessionMgr.GetSession(u.UserID)
		if sess != nil {
			status.IsOnline = true
			status.ConnectedAt = sess.ConnectedAt
			sess.mu.Lock()
			status.TxSpeed = sess.CurrentTxSpeed
			status.RxSpeed = sess.CurrentRxSpeed
			sess.mu.Unlock()
		}

		users = append(users, status)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(users)
}

func (ws *WebServer) handleAddUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var newUser UserInfo
	if err := json.NewDecoder(r.Body).Decode(&newUser); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if newUser.UserID == "" || newUser.Token == "" || newUser.Subdomain == "" {
		http.Error(w, "Missing required fields", http.StatusBadRequest)
		return
	}

	newUser.CreatedAt = time.Now()

	ws.userStore.mu.Lock()
	ws.userStore.users[newUser.UserID] = &newUser
	ws.userStore.mu.Unlock()

	err := ws.userStore.SaveToFile(ws.cfg.UsersFile)

	if err != nil {
		http.Error(w, "Failed to save users", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusCreated)
}

func (ws *WebServer) handleDeleteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	userID := r.URL.Query().Get("id")
	if userID == "" {
		http.Error(w, "Missing user id", http.StatusBadRequest)
		return
	}

	ws.userStore.mu.Lock()
	delete(ws.userStore.users, userID)
	ws.userStore.mu.Unlock()

	err := ws.userStore.SaveToFile(ws.cfg.UsersFile)

	if err != nil {
		http.Error(w, "Failed to save users", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
}

func (ws *WebServer) handleEditUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var update UserInfo
	if err := json.NewDecoder(r.Body).Decode(&update); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if update.UserID == "" {
		http.Error(w, "Missing user id", http.StatusBadRequest)
		return
	}

	if err := ws.userStore.UpdateUser(update.UserID, update.Subdomain, update.Token); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if err := ws.userStore.SaveToFile(ws.cfg.UsersFile); err != nil {
		http.Error(w, "Failed to save users", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
}

func (ws *WebServer) handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]bool{
			"allow_public_mode": ws.cfg.AllowPublicMode,
			"maintenance_mode":  ws.cfg.MaintenanceMode,
		})
		return
	}

	if r.Method == http.MethodPut {
		var update struct {
			AllowPublicMode *bool `json:"allow_public_mode"`
			MaintenanceMode *bool `json:"maintenance_mode"`
		}
		if err := json.NewDecoder(r.Body).Decode(&update); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		if update.AllowPublicMode != nil {
			ws.cfg.AllowPublicMode = *update.AllowPublicMode
		}
		if update.MaintenanceMode != nil {
			ws.cfg.MaintenanceMode = *update.MaintenanceMode
		}

		// Save config. Note this re-marshals the whole config struct.
		// For a simple app this is fine as long as Config knows its file path.
		// We'll write it to "config.json" directly, or ws.cfg should know it.
		// But Wait: Config doesn't store its own path. We should pass configPath to SaveConfig.
		// Let's assume it's always "config.json" to simplify, or maybe the admin runs it with default.
		// Actually, I can just save it to "config.json"
		if err := ws.cfg.SaveConfig(ws.configPath); err != nil {
			http.Error(w, "Failed to save config", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusOK)
		return
	}

	http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
}

func (ws *WebServer) Start() error {
	mux := http.NewServeMux()

	// Serve the embedded static file
	staticFS, err := fs.Sub(webFS, "web")
	if err != nil {
		return err
	}
	mux.Handle("/", http.FileServer(http.FS(staticFS)))

	// API endpoints
	mux.HandleFunc("/api/users", ws.authMiddleware(ws.handleGetUsers))
	mux.HandleFunc("/api/users/add", ws.authMiddleware(ws.handleAddUser))
	mux.HandleFunc("/api/users/edit", ws.authMiddleware(ws.handleEditUser))
	mux.HandleFunc("/api/users/delete", ws.authMiddleware(ws.handleDeleteUser))
	mux.HandleFunc("/api/config", ws.authMiddleware(ws.handleConfig))

	addr := fmt.Sprintf(":%d", ws.cfg.WebPort)
	log.Printf("[web] starting management UI on %s", addr)

	// Determine if we should use TLS. If the cert and key files are provided,
	// and they exist, we use them.
	// We'll use ListenAndServeTLS since the control channel uses it and it's recommended.
	if ws.cfg.CertFile != "" && ws.cfg.KeyFile != "" {
		log.Printf("[web] using TLS")
		return http.ListenAndServeTLS(addr, ws.cfg.CertFile, ws.cfg.KeyFile, mux)
	}
	return http.ListenAndServe(addr, mux)
}
