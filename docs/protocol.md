# Relay tunnel protocol (v1)

The DSH plugin keeps one outbound WebSocket to the relay. The relay multiplexes
HTTP requests and WebSocket streams for the paired instance over that socket.
Nothing on the DSH machine listens publicly.

```
Android / browser
      │ HTTPS + device cookie
      ▼
relay (Caddy TLS)  ── one WSS per DSH instance ──▶  DSH plugin
                                                       │ HTTP / WS
                                                       ▼
                                                 127.0.0.1:3080
```

## Transport

- One WebSocket, `GET /tunnel`, upgraded over TLS (`wss://`).
- The relay authenticates the instance before the upgrade completes:
  - `Authorization: Bearer <instance-token>`
  - `X-DSH-Instance: <instance-id>`
- The token is compared against a SHA-256 hash stored by the relay. Tokens are
  never written to logs.
- A newer connection for the same instance replaces the older one (the older
  socket is closed with code `4001`).
- WebSocket `permessage-deflate` is disabled. Text frames are not used.
- The relay sends WebSocket ping frames every 25 s; a peer that does not answer
  within 60 s is disconnected.

## Frame format

Every WebSocket binary message is one frame:

```
+--------+----------------+------------------+
| type   | stream id      | payload          |
| 1 byte | uint32 BE      | 0 .. 64 KiB      |
+--------+----------------+------------------+
```

| Type | Name | Payload |
|---:|---|---|
| 1 | `OPEN` | JSON `{kind, method, path, headers}` |
| 2 | `HEAD` | JSON `{status, headers}` |
| 3 | `DATA` | HTTP body bytes, or a WebSocket fragment with a 1-byte flags prefix |
| 4 | `END` | JSON `{reason?}` |
| 5 | `RESET` | JSON `{code, message}` |
| 6 | `WINDOW` | JSON `{credit}` |
| 7 | `CLOSE` | JSON `{code, reason}` (WebSocket close) |
| 8 | `CONTROL` | JSON; stream id is always `0` |
| 9 | `PING` | empty |
| 10 | `PONG` | empty |

`DATA` frames are at most 64 KiB. Implementations may use smaller chunks.

For `kind = "http"` the `DATA` payload is the raw request or response body.

For `kind = "ws"` the first payload byte is a flags field:

| Bit | Meaning |
|---:|---|
| `0x01` | the WebSocket message is binary (otherwise text) |
| `0x02` | more fragments of the same message follow |

The remaining bytes are a fragment of the WebSocket message. A zero-length
message is one `DATA` frame with no fragment bytes. The receiver reassembles
fragments and delivers one WebSocket message. Reassembly is bounded by
`MAX_WS_MESSAGE_BYTES` (8 MiB by default).

## Streams

The relay initiates every stream. Stream ids increase from 1; they are never
reused on one connection.

- `OPEN.kind = "http"` carries an HTTP request. The plugin opens a request to
  the local DSH server, streams the request body from `DATA` frames, and
  answers with `HEAD`, `DATA`, and `END`. Either side may send `RESET`.
- `OPEN.kind = "ws"` carries a WebSocket upgrade. After a successful upstream
  upgrade the plugin sends `HEAD` with status `101`; afterwards both sides
  exchange WebSocket payloads as `DATA` frames and finish with `CLOSE`.
- Hop-by-hop headers are removed by both sides. The relay strips its own device
  cookie before forwarding; the plugin adds the DSH session cookie.

## Flow control

Each direction on a stream starts with a receive window of 1 MiB.

- A receiver decrements its window for each `DATA` payload.
- When the window falls below half, it sends `WINDOW {credit}` to restore it.
- A sender must not send more `DATA` bytes than its remaining credit. It waits
  for `WINDOW` frames when the credit is exhausted.
- A `WINDOW` frame always grants credit for the peer's sending direction on the
  same stream.

This keeps a phone upload or a large file download from buffering in the relay
process.

## Control messages

Control messages use type `CONTROL`, stream id `0`, and a JSON `type` field.

Relay → plugin:

```json
{"type":"hello","protocol":1,"instanceId":"default","publicHost":"default.relay.example.com"}
{"type":"pair/code","id":"...","code":"ABCDEFGHIJ","url":"https://default.relay.example.com/pair?code=ABCDEFGHIJ","expiresAt":1770000000000}
{"type":"pair/error","id":"...","message":"..."}
{"type":"devices","id":"...","devices":[{"id":"...","name":"Pixel","createdAt":...,"lastSeenAt":...}]}
{"type":"devices/error","id":"...","message":"..."}
{"type":"device/paired","device":{"id":"...","name":"Pixel","createdAt":...}}
{"type":"pong","id":"..."}
```

Plugin → relay:

```json
{"type":"ready","protocol":1}
{"type":"pair/create","id":"...","ttlMs":120000}
{"type":"devices/list","id":"..."}
{"type":"device/revoke","id":"...","deviceId":"..."}
{"type":"ping","id":"..."}
```

`id` correlates a request with its response. A response with the same `id` and
a matching `pair/*`, `devices/*` (or `pong`) type settles the request. The
plugin times out requests after 10 s.

## Pairing

1. The plugin sends `pair/create` over the tunnel.
2. The relay creates a one-time code (10 characters, 120 s TTL), stores only its
   SHA-256 hash, and answers with the full pairing URL.
3. The plugin renders the URL as a QR code.
4. The phone opens the URL on the instance host.
5. The relay consumes the code, creates a device record, sets an `HttpOnly`
   device cookie for the instance host, and redirects to `/`.
6. The relay tells the plugin `device/paired` and revokes the code.

The relay rejects a code after one use, after its TTL, or when it was issued by
a different instance.

## Authentication boundaries

| Hop | Credential | Notes |
|---|---|---|
| plugin → relay WSS | instance token (bearer) | TLS, hashed at rest |
| phone → relay | device cookie (HMAC) | `Secure`, `HttpOnly`, `SameSite=Lax`, host-only |
| phone → DSH | DSH signed session cookie | minted by the plugin on the DSH host; never leaves the machine |
| relay → DSH | none | the plugin injects the DSH cookie |

The DSH launch token is read by the plugin through the public
`connection.authenticatedUrl()` service. Neither the relay nor the phone sees
it.

## Versioning

`hello.protocol` is `1`. A peer that does not support the value closes with
WebSocket code `4002`. Additive control messages are allowed without a version
bump; framing or stream-semantic changes require a new protocol version.
