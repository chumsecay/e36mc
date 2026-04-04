# e36mc — Self-Hosted Minecraft Tunnel

A mini [e4mc](https://e4mc.link)/[playit](https://playit.gg) alternative that runs entirely on **your own VPS**. Install a Fabric mod, open your world to LAN, and friends can join from anywhere via `username.mc.yourdomain.com`.

## How It Works

```
┌─────────────┐                    ┌──────────────────┐                    ┌──────────────┐
│  External    │   plain TCP :25565 │    Relay Server   │   TLS :25500      │  Minecraft   │
│  Player      │ ──────────────────►│    (your VPS)     │◄─────────────────  │  + e36mc mod │
│              │   (Minecraft       │                   │   (control +      │  (host)      │
│              │    protocol)       │  MC handshake     │    data channels)  │              │
│              │                    │  routing by       │                    │  localhost   │
│              │◄──────────────────│  subdomain        │──────────────────►│  :LAN_PORT   │
└─────────────┘                    └──────────────────┘                    └──────────────┘
```

## Quick Start

### 1. VPS Setup (Relay Server)

**Prerequisites:** Go 1.22+, a domain with wildcard DNS

```bash
# DNS: Point *.mc.yourdomain.com → your VPS IP
# Example: *.mc.example.com  A  203.0.113.10

# TLS cert (Let's Encrypt wildcard via DNS challenge)
sudo certbot certonly --manual --preferred-challenges dns \
  -d "*.mc.yourdomain.com" -d "mc.yourdomain.com"

# Build and run
cd relay-server
go mod tidy
go build -o e36mc-relay

# Edit config
cp config.json config.json.bak
# Edit config.json with your domain and cert paths

# Edit users
# Edit users.json with your users and tokens

# Run
./e36mc-relay -config config.json
```

**config.json:**
```json
{
  "control_port": 25500,
  "public_port": 25565,
  "domain": "mc.yourdomain.com",
  "cert_file": "/etc/letsencrypt/live/mc.yourdomain.com/fullchain.pem",
  "key_file": "/etc/letsencrypt/live/mc.yourdomain.com/privkey.pem",
  "users_file": "users.json"
}
```

**users.json:**
```json
[
  {"user_id": "alice", "token": "generate-a-secure-random-token", "subdomain": "alice"},
  {"user_id": "bob", "token": "another-secure-token", "subdomain": "bob"}
]
```

### 2. Mod Setup (Players/Hosts)

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21+
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Build the mod: `cd mod && ./gradlew build`
4. Copy `mod/build/libs/e36mc-1.0.0.jar` to your `.minecraft/mods/` folder
5. Launch Minecraft, a default config will be created at `config/e36mc.json`
6. Edit `config/e36mc.json`:

```json
{
  "relay_host": "mc.yourdomain.com",
  "relay_port": 25500,
  "user_id": "alice",
  "token": "your-token-here",
  "trust_all_certs": false
}
```

7. Open a singleplayer world → **Open to LAN**
8. The mod will display your public address in chat: `alice.mc.yourdomain.com`
9. Share this address with friends!

## Architecture

| Component | Tech | Port | Protocol |
|-----------|------|------|----------|
| Public Minecraft | Go TCP listener | 25565 | Plain TCP (MC handshake routing) |
| Control Channel | Go TLS listener | 25500 | TLS + length-prefixed JSON |
| Mod Client | Java (Fabric) | — | TLS client to relay |

### Wire Protocol

Messages use **4-byte big-endian length prefix + JSON**:

| Type | Direction | Purpose |
|------|-----------|---------|
| `auth` | Mod→Relay | Authenticate with user_id + token |
| `auth_ok` | Relay→Mod | Return assigned domain |
| `new_conn` | Relay→Mod | External player connected |
| `conn_ready` | Mod→Relay | Data channel ready for conn_id |
| `ping`/`pong` | Both | Heartbeat (15s interval, 45s timeout) |

### Connection Flow

1. Mod opens TLS → Relay control port, sends `auth`
2. Relay validates → sends `auth_ok` with domain
3. External player connects to `alice.mc.yourdomain.com:25565`
4. Relay parses MC handshake → extracts subdomain → finds session
5. Relay sends `new_conn` to mod with a `conn_id`
6. Mod opens new TLS connection → Relay, sends `conn_ready`
7. Mod connects to `localhost:LAN_PORT`
8. Relay bridges: Player ↔ data channel ↔ Mod ↔ LAN server

## Security

- Control/data channels use **TLS** (Let's Encrypt)
- Each user has a unique **token** (constant-time comparison)
- One active session per user (new login kicks old)
- MC public port is plain TCP (standard Minecraft protocol)

## Project Structure

```
e36mc/
├── relay-server/           # Go relay server (for VPS)
│   ├── main.go             # Entry point
│   ├── config.go           # Configuration
│   ├── protocol.go         # Wire protocol
│   ├── auth.go             # User authentication
│   ├── session.go          # Session management
│   ├── control.go          # Control channel handler
│   ├── proxy.go            # MC handshake parser + TCP forwarding
│   ├── config.json         # Sample config
│   └── users.json          # Sample users
│
└── mod/                    # Fabric mod (for Minecraft)
    ├── build.gradle
    ├── gradle.properties   # MC 1.21.4 target
    └── src/main/
        ├── java/com/e36mc/
        │   ├── E36mcMod.java           # Mod entry point
        │   ├── TunnelClient.java       # Tunnel connection manager
        │   ├── TunnelProtocol.java     # Wire protocol (Java)
        │   ├── LanEventHandler.java    # Chat UI
        │   └── mixin/
        │       └── IntegratedServerMixin.java  # LAN detection
        └── resources/
            ├── fabric.mod.json
            └── e36mc.mixins.json
```

## License

MIT
