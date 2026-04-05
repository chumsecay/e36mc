package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"log"
)

func main() {
	configPath := flag.String("config", "config.json", "path to config file")
	flag.Parse()

	// Load config
	cfg, err := LoadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	log.Printf("[main] config loaded: control=%d, public=%d, domain=%s",
		cfg.ControlPort, cfg.PublicPort, cfg.Domain)

	// Load users
	userStore := NewUserStore()
	if err := userStore.LoadFromFile(cfg.UsersFile); err != nil {
		log.Fatalf("Failed to load users: %v", err)
	}
	log.Printf("[main] users loaded from %s", cfg.UsersFile)

	// Init session manager
	sessionMgr := NewSessionManager()

	// Start public Minecraft listener (plain TCP, port 25565)
	if err := startPublicListener(cfg.PublicPort, cfg.Domain, sessionMgr); err != nil {
		log.Fatalf("Failed to start public listener: %v", err)
	}

	// Load TLS cert for control channel
	cert, err := tls.LoadX509KeyPair(cfg.CertFile, cfg.KeyFile)
	if err != nil {
		log.Fatalf("Failed to load TLS cert: %v", err)
	}
	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}

	// Start control channel listener (TLS)
	controlAddr := fmt.Sprintf(":%d", cfg.ControlPort)
	ln, err := tls.Listen("tcp", controlAddr, tlsConfig)
	if err != nil {
		log.Fatalf("Failed to start control listener: %v", err)
	}
	log.Printf("[main] control listener (TLS) on %s", controlAddr)

	// Start Web Server
	webServer := NewWebServer(cfg, userStore, sessionMgr)
	go func() {
		if err := webServer.Start(); err != nil {
			log.Fatalf("Failed to start web server: %v", err)
		}
	}()

	// Accept control connections
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("[main] accept error: %v", err)
			continue
		}
		log.Printf("[main] new control connection from %s", conn.RemoteAddr())
		go handleControlConnection(conn, userStore, sessionMgr, cfg.Domain)
	}
}

// --- Utility: generate self-signed cert for development ---
// Run: go run gen_cert.go
// Or use Let's Encrypt certbot:
//   certbot certonly --manual --preferred-challenges dns -d "*.mc.yourdomain.com" -d "mc.yourdomain.com"

func printUsage() {
	fmt.Println(`
e36mc Relay Server

Usage: relay-server [options]

Options:
  -config string  Path to config.json (default "config.json")

Config file format (config.json):
{
  "control_port": 25500,
  "public_port": 25565,
  "domain": "mc.yourdomain.com",
  "cert_file": "cert.pem",
  "key_file": "key.pem",
  "users_file": "users.json"
}

Users file format (users.json):
[
  {"user_id": "alice", "token": "secret-token-1", "subdomain": "alice"},
  {"user_id": "bob", "token": "secret-token-2", "subdomain": "bob"}
]

DNS Setup:
  *.mc.yourdomain.com  A  <VPS_IP>
  mc.yourdomain.com    A  <VPS_IP>

TLS (Let's Encrypt):
  certbot certonly --manual --preferred-challenges dns \
    -d "*.mc.yourdomain.com" -d "mc.yourdomain.com"
`)
}

// init registers the printUsage function for -help
func init() {
	flag.Usage = func() {
		printUsage()
	}
}
