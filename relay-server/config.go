package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

type Config struct {
	// Port for mod control+data connections (TLS)
	ControlPort int `json:"control_port"`
	// Port for external Minecraft players (plain TCP, handshake-routed)
	PublicPort int `json:"public_port"`
	// Port for the management Web UI
	WebPort int `json:"web_port"`
	// Base domain, e.g. "mc.mydomain.com"
	Domain string `json:"domain"`
	// TLS certificate and key files (Let's Encrypt)
	CertFile string `json:"cert_file"`
	KeyFile  string `json:"key_file"`
	// Path to users.json
	UsersFile string `json:"users_file"`
	// Admin token for Web UI authentication
	AdminToken string `json:"admin_token"`
	// Toggles
	AllowPublicMode bool `json:"allow_public_mode"`
	MaintenanceMode bool `json:"maintenance_mode"`
}

func LoadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}

	// Defaults
	if cfg.ControlPort == 0 {
		cfg.ControlPort = 25500
	}
	if cfg.PublicPort == 0 {
		cfg.PublicPort = 25565
	}
	if cfg.WebPort == 0 {
		cfg.WebPort = 8080
	}
	if cfg.UsersFile == "" {
		cfg.UsersFile = "users.json"
	}
	return &cfg, nil
}

func (c *Config) SaveConfig(path string) error {
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal config: %w", err)
	}
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".config-*.tmp")
	if err != nil {
		return fmt.Errorf("create temp config: %w", err)
	}
	tmpPath := tmp.Name()
	defer func() {
		tmp.Close()
		os.Remove(tmpPath)
	}()
	if _, err := tmp.Write(data); err != nil {
		return fmt.Errorf("write temp config: %w", err)
	}
	if err := tmp.Chmod(0644); err != nil {
		return fmt.Errorf("chmod temp config: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp config: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("rename temp config: %w", err)
	}
	return nil
}
