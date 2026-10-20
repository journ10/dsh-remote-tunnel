# Deployment

## Prerequisites

- A Linux VPS (Ubuntu 22.04/24.04 or Debian 12 recommended).
- A domain you control. The default layout uses:
  - `relay.example.com` for the relay itself;
  - `*.relay.example.com` for instance hosts.
- Ports 80 and 443 reachable from the internet. Port 22 is only for your own
  administration; the relay does not need it.
- Docker Engine + Compose plugin (`install.sh --install-docker` can install it).

The DSH machine needs only outbound 443. It does not need a public IP, port
forwarding, or an SSH server.

## DNS

Create both records before starting Caddy:

```text
A  relay.example.com      -> <server IPv4>
A  *.relay.example.com    -> <server IPv4>
```

Caddy uses on-demand TLS: when a client asks for
`default.relay.example.com`, Caddy asks the relay's
`/internal/tls/ask` endpoint whether that host belongs to a configured instance,
then obtains a certificate over HTTP-01. Wildcard DNS is required so every
instance name resolves; a wildcard certificate is not required.

If you use a different layout:

```sh
sudo bash install.sh \
  --domain example.com \
  --relay-host relay.example.com \
  --instance-domain relay.example.com \
  --email you@example.com
```

Then the DNS records are `relay.example.com` and `*.relay.example.com`.

## Install

```sh
git clone <repository> dsh-remote-tunnel
cd dsh-remote-tunnel/server
sudo bash install.sh --domain example.com --email you@example.com
```

The script is idempotent. On a rerun it preserves the cookie secret and instance
token in `.env` unless you pass `--rotate-token`.

What it creates:

```text
/opt/dsh-remote-tunnel/
├── .env                    # 0600; secrets
├── Caddyfile
├── docker-compose.yml
├── Dockerfile
├── package.json
└── src/
```

Containers:

- `relay`: Node process on the internal Compose network; management port bound
  to `127.0.0.1:18080`.
- `caddy`: the only public listener (80/443).

Volumes:

- `relay-data`: SQLite database.
- `caddy-data`: certificates and keys.
- `caddy-config`: Caddy autosave state.

## Upgrade from 0.1.0

The 0.2.0 relay adds the one-time enrollment API. Upload the new release and run
the upgrade from the extracted directory:

```sh
sudo rm -rf /opt/dsh-relay-update
sudo mkdir -p /opt/dsh-relay-update
sudo tar -xzf dsh-remote-tunnel-server-0.2.0.tar.gz -C /opt/dsh-relay-update
cd /opt/dsh-relay-update/dsh-remote-tunnel-server-0.2.0
sudo bash install.sh --upgrade
```

`--upgrade` copies the new source into `/opt/dsh-remote-tunnel`, rebuilds the
image, and keeps `.env` (instance token and cookie secret). Verify the health
endpoint reports `"version":"0.2.0"`.

## One-time enrollment

Instead of copying the instance token from the server to the Mac:

1. Open **Plugins → dsh-remote-tunnel → 配置 → 服务器连接**.
2. Click **从服务器自动导入**.
3. The page shows a 10-character code and a command. Run it on the server:

```sh
cd /opt/dsh-remote-tunnel
sudo docker compose exec -T relay node src/cli.js enroll <CODE>
```

4. The plugin polls over HTTPS, receives the token, writes it to the 0600 token
   file, and reconnects.

Codes expire after five minutes and can be taken once. The relay keeps the token
in memory only between claim and takeover.

## Verify

```sh
sudo bash install.sh --status
curl -fsS https://relay.example.com/healthz
```

`healthz` returns `{"ok":true,...,"online":1}` once the DSH plugin connects.

```sh
curl -fsS https://default.relay.example.com/ -o /dev/null -w '%{http_code}\n'
# 401 before pairing; that is expected
```

## DSH plugin configuration

Install the plugin and add the row printed by the installer to the profile's
`cordis.patch.yml`. Write the instance token to the token file with mode 0600.
Restart `dsh web`.

The plugin's `domain` must match the instance public host. The bundle patch
reads it for `connection.trustedHosts` and `web-runtime.publicUrl`; changing it
requires a restart.

If DSH returns `403` after pairing, the public host is not in `trustedHosts`.
Check the profile patch and restart.

## Multiple instances

The relay data model supports multiple instances. Add one with the CLI:

```sh
cd /opt/dsh-remote-tunnel
sudo docker compose exec relay node src/cli.js instance add laptop
```

The command prints a token and the public host. Add a new DSH plugin row/profile
with that `instanceId`, `domain`, and token. Caddy will issue a certificate for
the new host on first request.

Revoke an instance:

```sh
sudo docker compose exec relay node src/cli.js instance revoke laptop
sudo docker compose restart relay   # drop an existing tunnel
```

## Operations

```sh
sudo bash install.sh --status       # containers + health
sudo bash install.sh --logs         # follow both services
sudo bash install.sh --upgrade      # rebuild and restart
sudo bash install.sh --uninstall    # stop; keep volumes
sudo bash install.sh --uninstall --purge  # stop and delete data
```

### Backups

Back up:

1. `relay-data` volume (`relay.sqlite`) — instances, devices, audit.
2. `caddy-data` volume — certificates (regenerable, but avoids re-issuance).
3. Server `.env` — cookie secret and instance token.
4. DSH plugin token file — the same instance token.

Losing the cookie secret invalidates every device cookie. Losing the instance
token requires rotating it in `.env` and the plugin token file.

### Rotating secrets

```sh
# Instance token: generate a new one, update .env, restart relay, update plugin.
sudo bash install.sh --rotate-token
```

Rotating the cookie secret (invalidate all phones):

```sh
sudo sed -i "s/^COOKIE_SECRET=.*/COOKIE_SECRET=$(openssl rand -hex 32)/" /opt/dsh-remote-tunnel/.env
sudo docker compose -f /opt/dsh-remote-tunnel/docker-compose.yml up -d
```

Then pair every phone again.

## Firewall

Allow only:

- 80/tcp, 443/tcp, 443/udp (Caddy/HTTP-3);
- your administration port (SSH or a VPN).

Do not publish the relay management port beyond localhost. Do not expose DSH.

## Troubleshooting

| Symptom | Check |
|---|---|
| Caddy cannot get a certificate | DNS A records; port 80 reachable; `docker compose logs caddy` |
| `healthz` shows `online:0` | DSH plugin logs; `relayUrl`, token file, instance id; outbound 443 |
| Pairing page says code invalid | code expired or already used; generate a new QR |
| Relay returns 401 after pairing | device cookie missing/expired; pair again |
| DSH returns 403 through relay | `domain` not in `trustedHosts`; restart `dsh web` |
| DSH returns 503 | instance tunnel offline; check plugin/DSH |
| WebSocket fails but HTTP works | proxy/CDN in front of Caddy; allow upgrade; Caddy `flush_interval -1` |
| `database is locked` | one relay replica only; SQLite is not multi-writer across hosts |
| Android cannot connect | Use HTTPS with a system-trusted certificate; verify the instance address and pairing code |

## Limits

- One relay process per SQLite database. Horizontal scaling needs a shared
  database and sticky tunnel routing, which v0.1 does not implement.
- On-demand TLS is rate-limited by Let's Encrypt per registered domain. For many
  instances, issue a wildcard certificate with a DNS-01 Caddy module instead.
- v1 terminates TLS at the relay. See the security boundary in
  [`security.md`](security.md).
