package main

import (
	"encoding/json"
	"fmt"
	"os"
)

type Config struct {
	// Port for mod control+data connections (TLS)
	ControlPort int `json:"control_port"`
	// Port for external Minecraft players (plain TCP, handshake-routed)
	PublicPort int `json:"public_port"`
	// Base domain, e.g. "mc.mydomain.com"
	Domain string `json:"domain"`
	// TLS certificate and key files (Let's Encrypt)
	CertFile string `json:"cert_file"`
	KeyFile  string `json:"key_file"`
	// Path to users.json
	UsersFile string `json:"users_file"`
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
	if cfg.UsersFile == "" {
		cfg.UsersFile = "users.json"
	}
	return &cfg, nil
}
