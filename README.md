# e36mc — Self-Hosted Minecraft Tunnel

A complete self-hosted alternative to [e4mc](https://e4mc.link) or [playit](https://playit.gg) that runs entirely on **your own VPS**. Install the Fabric mod, open your world to LAN, and friends can join from anywhere seamlessly via `username.mc.yourdomain.com`.

With the built-in **Web Dashboard**, you can monitor live network speeds, user states, and add/edit users on the fly.

## Features
- 🚀 **100% Self-Hosted**: No third-party network bottlenecks.
- 🔒 **Secure Data Channel**: Mod to Relay traffic is fully TLS-encrypted.
- ⚡ **Minecraft TCP Routing**: External players connect via standard Minecraft TCP without any extra mods on their end.
- 🖥️ **Sleek Web Dashboard**: Monitor live bandwidth speed (Tx/Rx), uptime, and manage players natively with a modern UI.

---

## ☁️ 1. VPS Server Setup (Relay Server)

**Prerequisites:** Go 1.22+, a VPS with a public IP, and a domain managed by Cloudflare.

### Step 1: Wildcard DNS Setup
Assume you own `example.com`.
1. Go to Cloudflare DNS and add **two A records**:
   - `mc` -> `YOUR_VPS_IP`
   - `*.mc` -> `YOUR_VPS_IP`
> [!WARNING]
> Set the proxy status to **DNS only** (Grey cloud ☁️). Cloudflare Proxy (Orange cloud) blocks native Minecraft TCP traffic!

### Step 2: Let's Encrypt TLS Certificate
The relay requires a Wildcard Certificate (`*.mc.example.com`).

```bash
sudo apt update
sudo apt install certbot python3-certbot-dns-cloudflare

# Store your Cloudflare API token
sudo mkdir -p /etc/cloudflare
sudo tee /etc/cloudflare/credentials.ini > /dev/null <<EOF
dns_cloudflare_api_token = YOUR_CLOUDFLARE_API_TOKEN_HERE
EOF
sudo chmod 600 /etc/cloudflare/credentials.ini

# Generate Certs
sudo certbot certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials /etc/cloudflare/credentials.ini \
  -d "mc.example.com" \
  -d "*.mc.example.com"
```

### Step 3: Build & Configure
```bash
# Clone the repo and build the relay
git clone https://github.com/chumsecay/e36mc.git
cd e36mc/relay-server
go build -o e36mc-relay .

# Copy config templates
cp config.json config.json.bak
cp users.json users.json.bak
```

Edit `config.json` with your domain and exact cert paths:
```json
{
  "control_port": 25500,
  "public_port": 25565,
  "domain": "mc.example.com",
  "cert_file": "/etc/letsencrypt/live/mc.example.com/fullchain.pem",
  "key_file": "/etc/letsencrypt/live/mc.example.com/privkey.pem",
  "users_file": "users.json",
  "admin_token": "YOUR_SUPER_SECRET_WEB_PASSWORD",
  "web_port": 25500
}
```

### Step 4: Run the Server
You can run it directly:
```bash
sudo ./e36mc-relay -config config.json
```
> [!TIP]
> For production, it is highly recommended to run this executable via `systemd` or inside a `screen`/`tmux` session.

---

## 🌐 2. Web Dashboard Management

Once the relay server is running, head to your Web Dashboard:
**URL:** `https://mc.example.com:25500` (or `http://YOUR_VPS_IP:25500` if accessing directly).
*(Note: If you run it locally without DNS, you may need to bypass the browser's untrusted certificate warning).*

1. **Login:** Enter the `admin_token` you configured in `config.json`.
2. **Dashboard Features:**
   - **User Connections:** View all configured players.
   - **Live Bandwidth:** Real-time upload (↑) and download (↓) speed of active tunnels.
   - **Add/Edit Users:** Assign custom subdomains (e.g. `alice` -> `alice.mc.example.com`) to whitelist players.

---

## 🎮 3. Mod Setup (Players / Hosts)

1. Drop `e36mc-1.0.0.jar` + `fabric-api.jar` into your `.minecraft/mods` folder.
2. Launch the game once so `config/e36mc.json` generates.
3. Edit `config/e36mc.json`:
   - Host the VPS: If the host is on the same local network as the VPS, you might need to set `"relay_host"` to your local VPS IP (e.g., `192.168.1.100`) to bypass internal hairpin NAT issues. External users can keep it as `mc.example.com`.
   - Update `trust_all_certs` to `true` if you use self-signed certificates or connect directly via IP.
4. **Open to LAN:** Play Singleplayer, pause the game, and hit **Open to LAN**.
5. **Whitelist Process:** 
   - The first time you Open LAN, the game will present your **User ID** and **Token** in the chat.
   - Send this ID and Token to the Server Administrator.
   - The Admin will add you via the Web Dashboard.
   - Re-open LAN, and it will bridge instantly! Share your domain (e.g., `alice.mc.example.com`) to your friends.

---

## ⚙️ How It Works (Architecture)

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

The relay uses standard Minecraft packet parsing. When an external player connects, the server reads the **Server Address** string in the very first packet. It looks for the subdomain (e.g. `bob.mc.example.com`) to reliably map the generic port `25565` connection to the exact player's Mod Tunnel session. 

### Security & Protocol
- Both the Web interface and the relay tunnels run securely over TLS.
- Passwords mapping and authorization are checked with Constant-Time strings to prevent timing attacks.
- Atomic execution and `RWMutex` locking ensure connections don't drop or conflict.

## License
MIT
