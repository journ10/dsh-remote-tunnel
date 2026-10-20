# Development

## Test matrix

| Layer | Command | What it proves |
|---|---|---|
| Relay unit | `cd server && npm test` | config, cookies, store, rate limits, protocol parity |
| Relay end-to-end | included in `npm test` | real relay + fake DSH peer: pair, HTTP, WS, revoke |
| Plugin unit | `cd plugin && pnpm test` | config, SSH argv, client registration, protocol parity |
| Plugin integration | included in `pnpm test` | real plugin client + fake relay + fake DSH: cookie injection, GET/POST streaming, WS, control |
| KMP | `cd mobile && ./gradlew :composeApp:jvmTest :androidApp:assembleDebug` | Offline shared tests and Android host build |
| Publication | `python3 scripts/audit_publication.py --staged` | Exact staged content checked for private data and generated files |

## Relay

```sh
cd server
npm install
npm test
```

For a local process without Docker:

```sh
PUBLIC_HOST=relay.test \
INSTANCE_BASE_DOMAIN=relay.test \
PUBLIC_URL=http://relay.test \
PORT=18080 \
COOKIE_SECRET=0123456789abcdef0123456789abcdef \
BOOTSTRAP_INSTANCE_ID=default \
BOOTSTRAP_INSTANCE_TOKEN=local-instance-token-0123456789 \
COOKIE_SECURE=false \
DATA_DIR=/tmp/dsh-relay-data \
TRUST_PROXY=false \
node src/index.js
```

Then use `Host: relay.test` or `Host: default.relay.test` with `curl` against
`127.0.0.1:18080`. In production Caddy supplies the Host and TLS.

## Plugin

```sh
cd plugin
pnpm install
pnpm test
```

The integration test starts its own fake relay and fake DSH server on loopback,
so it does not need a running DSH instance.

## Full local loop with a real DSH

This is the manual test used to validate the MVP. Use a **separate** temporary
`DSH_HOME` and port so your daily DSH is untouched.

### 1. Relay

```sh
cd server
PUBLIC_HOST=relay.test \
INSTANCE_BASE_DOMAIN=relay.test \
PUBLIC_URL=http://relay.test \
PORT=18080 \
COOKIE_SECRET=0123456789abcdef0123456789abcdef \
BOOTSTRAP_INSTANCE_ID=default \
BOOTSTRAP_INSTANCE_TOKEN=local-instance-token-0123456789 \
COOKIE_SECURE=false \
DATA_DIR=/tmp/dsh-e2e/relay-data \
TRUST_PROXY=false \
node src/index.js
```

### 2. Temporary DSH profile

From a DSH checkout:

```sh
export DSH_HOME=/tmp/dsh-e2e
node apps/cli/lib/bin.js plugin --profile web add /path/to/dsh-remote-tunnel/plugin
```

Write `/tmp/dsh-e2e/profiles/web/cordis.patch.yml`:

```yaml
- id: remote-tunnel
  config:
    mode: relay
    domain: default.relay.test
    relayUrl: ws://127.0.0.1:18080/tunnel
    instanceId: default
    localPort: 3199
    tokenFile: /tmp/dsh-e2e/dsh-remote-tunnel/instance-token
```

Write the token and start DSH:

```sh
mkdir -p /tmp/dsh-e2e/dsh-remote-tunnel
printf '%s\n' 'local-instance-token-0123456789' > /tmp/dsh-e2e/dsh-remote-tunnel/instance-token
chmod 600 /tmp/dsh-e2e/dsh-remote-tunnel/instance-token

DSH_HOME=/tmp/dsh-e2e node apps/cli/lib/bin.js web \
  --host 127.0.0.1 --port 3199 --no-open
```

The relay log should show `instance connected`.

### 3. Pair through the relay

Generate a code from the plugin's local route:

```sh
curl -sS -X POST -H 'Host: default.relay.test' \
  -H 'content-type: application/json' -d '{}' \
  http://127.0.0.1:3199/remote-tunnel/pair
```

Take `code` from the JSON and open it through the relay:

```sh
curl -sS -D /tmp/pair.headers -o /dev/null \
  -H 'Host: default.relay.test' \
  'http://127.0.0.1:18080/pair?code=<CODE>'
```

The response is `303` with `Set-Cookie: __dsh_device=...`. Use that cookie to
fetch the UI:

```sh
COOKIE=$(awk 'tolower($1)=="set-cookie:"{print $2}' /tmp/pair.headers | tr -d '\r')
curl -sS -H 'Host: default.relay.test' -H "Cookie: $COOKIE" \
  http://127.0.0.1:18080/ -o /tmp/index.html
```

`/tmp/index.html` should contain the DSH shell and a `plugins/??...` reference
to `dsh-remote-tunnel/client.js`. Fetch that combo URL with the same cookie to
confirm the plugin bundle is proxied.

The plugin status route should now report `cookieMinted: true`,
`connected: true`, and the paired device:

```sh
curl -sS -H 'Host: default.relay.test' \
  http://127.0.0.1:3199/remote-tunnel/status
```

### 4. Clean up

Stop the temporary DSH process and the relay process. Delete `/tmp/dsh-e2e` if
desired. Never reuse the test token or cookie secret in production.

## KMP client

See [platform toolchains](cross-platform.md) and [release/export paths](releases.md).

```sh
cd mobile
./gradlew :composeApp:jvmTest :androidApp:assembleDebug
```

Live JVM tests are opt-in. Supply `DSH_TEST_PAIR_URL` through your local shell or
secret manager and set `DSH_TEST_WORKSPACE` to an absolute path in a disposable
DSH checkout. Do not put either value in source, shell scripts or published CI
logs. Tests create sessions, may issue prompts/terminal commands and archive
their sessions afterward. File/Office tests assume the test process and DSH can
access that same workspace path. Without a pairing URL, live test methods return
without network access; they are not evidence of a live test pass.

The screenshot harness uses synthetic data only. Its output stays under a local
build report directory until selected images have been manually reviewed.

## Protocol changes

`plugin/lib/protocol.js` and `server/src/protocol.js` are intentionally
duplicated so each package installs standalone. The `protocol-parity` tests fail
on any drift. Change both copies together and update `docs/protocol.md`.
