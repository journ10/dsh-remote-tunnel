# dsh-remote-tunnel plugin

The DSH half of the project. It keeps one outbound WSS to a self-hosted relay,
multiplexes HTTP/WebSocket streams to the local DSH Web server, and injects a
DSH browser cookie minted on this machine.

## Install

```sh
# from the repository root
dsh plugin --profile web add ./plugin

# or from a published package
dsh plugin --profile web add dsh-remote-tunnel
```

Then edit the profile's `cordis.patch.yml`:

```yaml
- id: remote-tunnel
  config:
    mode: relay
    domain: default.relay.example.com
    relayUrl: wss://relay.example.com/tunnel
    instanceId: default
    localPort: 3080
    tokenFile: /home/you/.dsh/dsh-remote-tunnel/instance-token
```

Write the instance token with mode 0600:

```sh
mkdir -p ~/.dsh/dsh-remote-tunnel
chmod 700 ~/.dsh/dsh-remote-tunnel
printf '%s\n' '<token>' > ~/.dsh/dsh-remote-tunnel/instance-token
chmod 600 ~/.dsh/dsh-remote-tunnel/instance-token
```

Restart `dsh web` after installing the bundle or changing `domain`.

## Configure from the Plugins page

Open **Plugins → dsh-remote-tunnel → 配置**. The **服务器连接** card accepts:

- **Relay URL** — `wss://relay.example.com/tunnel`
- **实例 ID** — the id the server printed, e.g. `mac`
- **实例令牌** — the token printed by `server/install.sh`

Saving writes the token to `$DSH_HOME/dsh-remote-tunnel/instance-token` (mode
0600) and the non-secret URL/id to `settings.json` (mode 0600). The runtime
reconnects in place; **you do not need to restart DSH for a token or URL
change**. Changing `domain` still needs a restart because `trustedHosts` and
`publicUrl` are composition-time values.

The token is never written to `cordis.patch.yml` and is never returned by the
status API. If the token field is left blank, an existing token is kept.

### One-time enrollment (no long token copy)

Click **从服务器自动导入**. The page shows a 10-character code and a command
for the server:

```sh
cd /opt/dsh-remote-tunnel
sudo docker compose exec -T relay node src/cli.js enroll <CODE>
```

Run it in the server console or OrcaTerm. The relay CLI reads the instance token
from the container environment, posts it to the local relay, and the plugin
imports it over HTTPS. The code expires after five minutes and is single-use.


## Configuration

| Key | Default | Meaning |
|---|---|---|
| `mode` | `relay` | `relay` uses the HTTPS tunnel; `ssh` keeps the legacy reverse tunnel |
| `domain` | required | Public instance host, e.g. `default.relay.example.com`. Also feeds `trustedHosts` / `publicUrl` in the bundle patch |
| `relayUrl` | `''` | `wss://relay.example.com/tunnel` (or `https://relay.example.com`) |
| `instanceId` | `default` | Instance id registered on the relay |
| `instanceToken` | `''` | Inline token; prefer `tokenFile` |
| `tokenFile` | `$DSH_HOME/dsh-remote-tunnel/instance-token` | 0600 token file |
| `localHost` | `127.0.0.1` | Local DSH HTTP host |
| `localPort` | `3080` | Local DSH HTTP port |
| `extraTrustedHosts` | `[]` | Extra authorities for DSH's Host/Origin fence |
| `pairingEnabled` | `true` | Show the pairing UI |
| `remoteBind` | `127.0.0.1:3080` | SSH fallback bind |
| `sshHost` / `sshUser` / `sshPort` / `identityFile` | — | SSH fallback |

The instance token is never read from `cordis.patch.yml` when `tokenFile` is set.
Do not commit a real token.

## Routes

The Host registers three routes used by the Plugins page:

| Route | Purpose |
|---|---|
| `GET /remote-tunnel/status` | Connection state, relay URL, devices, cookie status |
| `POST /remote-tunnel/pair` | Ask the relay for a one-time code and return the QR SVG |
| `POST /remote-tunnel/revoke` | Revoke one paired device |

The relay gates these routes with its device cookie. The plugin also checks the
`Origin` header for local and public-origin requests.

## How the DSH cookie is minted

1. The plugin calls `ctx.get('connection').authenticatedUrl('http://127.0.0.1:<localPort>/')`.
2. It sends that tokenized URL to the local DSH server with
   `Host: <public domain>`.
3. DSH answers `303` with a signed `dsh-auth-*` cookie for that authority.
4. The plugin keeps only that cookie pair in memory and adds it to proxied
   requests and WebSocket handshakes.

Neither the relay nor the phone sees the launch token or the DSH cookie.

## SSH fallback

Set `mode: ssh` to run the original `ssh -N -T -R ...` supervisor. This is for
migration only; the WSS relay path is the supported deployment.

## Tests

```sh
pnpm install
pnpm test
```

`test/relay.test.mjs` runs the real plugin client against a fake relay and a
fake DSH HTTP/WS server. It covers cookie minting, GET/POST streaming,
WebSocket forwarding, flow-control frames, and pairing/device control messages.
`test/protocol-parity.mjs` fails if `lib/protocol.js` drifts from
`../server/src/protocol.js`.
