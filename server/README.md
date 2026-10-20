# dsh-remote-tunnel relay

A self-hosted HTTPS relay for DSH. Caddy terminates TLS; the Node relay
authenticates instances, issues device cookies, and multiplexes HTTP/WebSocket
traffic over one outbound tunnel per DSH instance.

## One-command install

Requirements: Ubuntu/Debian VPS, Docker + Compose, a domain, ports 80/443 free.

```sh
sudo bash install.sh --domain example.com --email you@example.com
```

Before start, add:

```text
A  relay.example.com      -> <server IPv4>
A  *.relay.example.com    -> <server IPv4>
```

The installer writes a 0600 `.env`, builds the image, starts Caddy + relay, and
prints the DSH plugin row and token-file command.

## Environment

| Key | Required | Default | Meaning |
|---|---|---|---|
| `PUBLIC_HOST` | yes | — | Relay hostname, e.g. `relay.example.com` |
| `INSTANCE_BASE_DOMAIN` | no | `PUBLIC_HOST` | Instance hosts are `<id>.<this>` |
| `PUBLIC_URL` | no | `https://PUBLIC_HOST` | Origin used to build pairing URLs |
| `COOKIE_SECRET` | yes | — | 32+ chars; signs device cookies |
| `ACME_EMAIL` | yes (Caddy) | — | Let's Encrypt account email |
| `BOOTSTRAP_INSTANCE_ID` | no | — | Instance id created on boot |
| `BOOTSTRAP_INSTANCE_TOKEN` | with id | — | 16+ chars; the DSH plugin token |
| `BOOTSTRAP_PUBLIC_HOST` | no | `<id>.<base>` | Public host for the bootstrap instance |
| `COOKIE_SECURE` | no | `true` for HTTPS | Set `false` only for local HTTP tests |
| `PAIR_TTL_MS` | no | `120000` | Pairing-code lifetime |
| `PAIR_CODE_LENGTH` | no | `10` | Pairing-code length (8–16) |
| `DEVICE_COOKIE_DAYS` | no | `30` | Device cookie lifetime |
| `RATE_LIMIT_PER_MINUTE` | no | `30` | Pairing attempts per IP |
| `MAX_REQUEST_BYTES` | no | `512 MiB` | `Content-Length` cap |
| `MAX_WS_MESSAGE_BYTES` | no | `8 MiB` | WebSocket message cap |
| `TRUST_PROXY` | no | `true` | Read `X-Forwarded-For` (behind Caddy) |
| `LOG_LEVEL` | no | `info` | `debug`, `info`, `warn`, `error` |
| `DATA_DIR` | no | `./data` | SQLite + runtime data |

## Endpoints

| Host | Path | Purpose |
|---|---|---|
| relay | `GET /healthz` | health + online instance count |
| relay | `GET /internal/tls/ask?domain=...` | Caddy on-demand TLS decision |
| relay | `GET /api/version` | relay/protocol version |
| relay | `GET /tunnel` (WSS) | DSH instance tunnel |
| instance | `GET /pair?code=...` | one-time pairing, sets device cookie, redirects |
| instance | everything else | authenticated reverse proxy to DSH |

## CLI

```sh
docker compose exec relay node src/cli.js instance list
docker compose exec relay node src/cli.js instance add laptop
docker compose exec relay node src/cli.js instance revoke laptop
docker compose exec relay node src/cli.js device list laptop
docker compose exec relay node src/cli.js device revoke laptop <device-id>
```

`instance add` prints a fresh token once. Store it in the DSH plugin token file.
After `instance revoke`, restart the relay to drop an existing tunnel.

## Data and backup

- `relay-data` volume: `relay.sqlite` (instances, pairing codes, devices, audit).
- `caddy-data` volume: TLS certificates and keys.
- `.env`: cookie secret and instance token.

Back up all three. Losing `COOKIE_SECRET` invalidates every device cookie; losing
the instance token requires rotating the plugin token.

## Operations

```sh
sudo bash install.sh --status
sudo bash install.sh --logs
sudo bash install.sh --upgrade
sudo bash install.sh --uninstall        # keeps volumes
sudo bash install.sh --uninstall --purge # deletes data
```

## Tests

```sh
npm install
npm test
```

The suite covers config validation, cookie signing/expiry, store semantics,
rate limiting, protocol parity, and a real relay + fake DSH tunnel end-to-end
test for pairing, HTTP, WebSocket, and revocation.

## Security

See [`../docs/security.md`](../docs/security.md). v1 terminates TLS at the relay;
self-host it on a machine you control.
