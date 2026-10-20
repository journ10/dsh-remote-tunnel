# Architecture

`dsh-remote-tunnel` is an open-source, self-hosted way to reach a DSH Web UI
from a phone. It has three parts that can be deployed independently.

```
┌────────────────────┐        HTTPS + device cookie        ┌──────────────────────┐
│  KMP client        │ ──────────────────────────────────▶ │  relay server        │
│  QR pairing        │                                     │  Caddy + relay       │
│  Shared Compose UI │                                     │  device registry     │
└────────────────────┘                                     └──────────┬───────────┘
                                                                       │ one outbound WSS
                                                                       │ per DSH instance
                                                                       ▼
                                                           ┌──────────────────────┐
                                                           │  DSH plugin          │
                                                           │  local proxy         │
                                                           │  127.0.0.1:3080      │
                                                           └──────────────────────┘
```

The DSH machine never accepts an inbound connection. It opens one outbound
WebSocket over TLS to the relay, which is reachable from the phone. This works
behind NAT, CGNAT, and firewalls that only allow outbound 443.

## Parts

### 1. DSH plugin (`plugin/`)

Runs in the DSH Web host process.

- Reads non-secret config from the DSH Loader row; reads the instance token from
  a `0600` file (never from the bundle patch).
- Maintains the outbound WSS connection with exponential backoff.
- Implements the relay protocol: one multiplexed HTTP/WS stream per request.
- Proxies every stream to `127.0.0.1:${localPort}`.
- Mints a DSH browser-session cookie for the public instance host with
  `connection.authenticatedUrl()` and injects it into proxied requests. The DSH
  launch token stays on the machine.
- Registers `/remote-tunnel/status`, `/remote-tunnel/pair`, and
  `/remote-tunnel/revoke` routes for its Plugins page.
- Renders the pairing QR code and the list of paired devices.
- Keeps the legacy SSH supervisor as an optional fallback (`mode: ssh`).

The bundle patch still owns `connection.trustedHosts` and
`web-runtime.publicUrl`; both are composition-time values and need a DSH
restart when the public host changes.

### 2. Relay server (`server/`)

A single-tenant-by-default, multi-instance-capable HTTPS gateway.

- Caddy terminates TLS on 80/443 and obtains certificates on demand.
- The relay process:
  - authenticates instances by token,
  - keeps one active tunnel per instance,
  - creates one-time pairing codes,
  - issues and verifies device cookies,
  - proxies HTTP and WebSocket traffic to the right tunnel,
  - enforces request size, rate limits, and audit logging,
  - persists instances, devices, and audit rows in SQLite.
- Instance hosts are `<instance-id>.<instance-base-domain>`.
- The relay host itself serves `/healthz`, the pairing landing page, and the
  Caddy on-demand TLS decision endpoint.
- Docker Compose is the supported deployment. `install.sh` generates secrets,
  writes `.env`, starts the stack, and prints the plugin configuration.

### 3. KMP client (`mobile/`)

The shared Compose client calls DSH RPC and streaming endpoints through the relay.

- Redeems the QR/link without following the redirect, stores the device cookie
  for that origin, and loads native Compose session/chat screens.
- Persists multiple instances on Android/iOS and isolates credentials by origin.
- Recreates session navigation, dialogs, coroutine scopes and WebSockets when
  switching or re-pairing an instance.
- Owns task lists, chat, approvals, questions, file/terminal tools and change views.
- Delegates platform transport, scanning, pickers, previews and browser handling
  to Android, iOS, JVM and experimental Harmony adapters.

The Android application is a thin wrapper around this shared module. A WebView
is used only for browser tools, not as the main session/chat UI. See
[platform support](cross-platform.md) for unfinished native adapters.

## Data flow

### Pairing

```
DSH plugin ── pair/create ──▶ relay
DSH plugin ◀── code + URL ─── relay
DSH plugin displays QR
phone scans URL ──▶ relay /pair?code=...
relay validates code, creates device, sets cookie, redirects to /
phone stores cookie, then calls DSH RPC/streams through relay and plugin
```

### Request

```
phone POST /api/session/list
  → Caddy (TLS)
  → relay verifies device cookie
  → tunnel OPEN(kind=http)
  → plugin injects DSH cookie
  → DSH 127.0.0.1:3080
  ← response HEAD/DATA/END
  ← relay writes to phone
```

### DSH restart

The relay device cookie survives a DSH restart. The plugin reconnects, mints a
new DSH cookie, and the next request succeeds. The client retries its live streams after a connection drop.

## Open-source constraints

- No personal domains, IP addresses, tokens, or certificates in the repository.
  Everything deployment-specific lives in `.env`, a `0600` token file, or the
  DSH profile patch.
- The plugin works with any relay URL; the server works with any domain.
- Tests use loopback and generated secrets.
- MIT license. Security reports follow `SECURITY.md`.
- The server image and the Android APK are buildable from source with the
  documented toolchains.
