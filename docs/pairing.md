# Pairing

## Flow

```
DSH plugin                     relay                         phone
    │                             │                             │
    │── pair/create ─────────────▶│                             │
    │◀── code + URL ──────────────│                             │
    │                             │                             │
    │   QR: https://<instance>/pair?code=XXXXXXXXXX             │
    │                             │◀── GET /pair?code=... ───────│
    │                             │  consume code (one use)     │
    │                             │  create device row          │
    │                             │  Set-Cookie: __dsh_device   │
    │                             │──── 303 / ─────────────────▶│
    │                             │◀── GET / (device cookie) ───│
    │◀── OPEN http / ─────────────│                             │
    │── inject DSH cookie ──▶ DSH │                             │
    │◀── DSH index ───────────────│──── 200 DSH UI ────────────▶│
```

The device cookie is host-only for the instance subdomain. The DSH session
cookie is minted by the plugin on the DSH machine and injected into every
proxied request; it is stripped from responses before they reach the phone.

## Code rules

- 10 characters from an unambiguous alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`).
- 120-second TTL (configurable, 15 s–15 min).
- One use; the relay marks it used atomically.
- Only a SHA-256 hash is stored.
- Per-IP and per-instance rate limits.
- Scoped to the instance host that issued it; a code cannot pair another instance.

## Device cookies

- Name: `__dsh_device`.
- Flags: `Secure` (production), `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`.
- Value: base64url payload + HMAC-SHA256 with `COOKIE_SECRET`.
- Payload: device id, instance id, absolute expiry.
- Every request also checks the device row; revocation wins immediately.
- Lifetime: 30 days by default.

## Revocation

From DSH: **Plugins → dsh-remote-tunnel → 撤销** next to the device. The plugin
sends a control message to the relay; the relay marks the device revoked and
the next request from the phone gets `401`.

From the server:

```sh
docker compose exec relay node src/cli.js device list default
docker compose exec relay node src/cli.js device revoke default <device-id>
```

## Revoking every device

Rotate the relay cookie secret and restart the relay. Every existing cookie
fails signature verification. See [deployment](deployment.md#rotating-secrets).

## QR safety

A QR code is a temporary bearer secret. Anyone who scans it first can pair. The
code is only valid for two minutes and only once. Generate it when you are ready
to scan; do not post a screenshot of it in a chat.

The KMP client redeems the supplied URL without following redirects, then stores
the cookie under that origin. It accepts HTTP for local development; use trusted
HTTPS URLs for real deployments. Only scan codes from your own DSH plugin. The
native client then calls the RPC/WebSocket endpoints instead of loading the Web UI.

## Optional approval

v0.1 pairs on code possession. A future version can require an explicit
"approve device" action on the DSH plugin page before the relay issues the
cookie. Contributions welcome; see `CONTRIBUTING.md`.
