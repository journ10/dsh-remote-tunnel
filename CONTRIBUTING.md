# Contributing

Thanks for helping make DSH reachable without exposing it to the internet.

## Ground rules

- Be respectful. Assume good intent.
- Never commit a real domain, IP address, instance token, cookie secret,
  certificate, keystore, or signing password.
- Use `example.com` / `relay.example.com` placeholders in docs and tests.
- Security-sensitive changes need a short threat-model note in the PR.
- Keep the plugin and relay protocol copies in sync; `protocol-parity` tests
  enforce it.

## Development

See [`docs/development.md`](docs/development.md) for the full local loop.

```sh
# Relay
cd server
npm install
npm test

# Plugin
cd plugin
pnpm install
pnpm test

# KMP client
cd mobile
./gradlew :composeApp:jvmTest :androidApp:assembleDebug
```

Requirements:

- Node.js 22.19+ for the plugin, Node.js 24+ for the relay (`node:sqlite`).
- JDK 17 and Android SDK 35 for the app.
- Docker + Compose for the deployment path.

## Pull requests

A good PR:

1. Explains the user-visible problem and the chosen approach.
2. Adds or updates tests. Protocol, auth, pairing, cookie, and routing changes
   need a test that fails before the fix.
3. Updates the relevant README or `docs/` page.
4. Keeps `plugin/lib/protocol.js` and `server/src/protocol.js` identical.
5. Does not add personal deployment values.
6. Passes:

```sh
(cd server && npm test)
(cd plugin && pnpm test)
(cd mobile && ./gradlew :composeApp:jvmTest :androidApp:assembleDebug)
python3 scripts/audit_publication.py
```

## Protocol changes

- Additive control messages can land without a version bump.
- Framing, stream semantics, flow-control, or auth changes require a protocol
  version bump in both copies and `docs/protocol.md`.
- New behavior must be backward compatible for at least one minor release, or
  fail loudly with a clear error.

## Release checklist

- [ ] Version bumped in `plugin/package.json` and `server/package.json`.
- [ ] `CHANGELOG.md` updated (create it when the first release is cut).
- [ ] Tests and Android debug build pass.
- [ ] Docker image builds from a clean checkout.
- [ ] No secrets, personal domains, or IPs in the diff.
- [ ] Plugin package includes `lib/`, `cordis.patch.yml`, `README.md`.
- [ ] Release notes include migration steps for config or protocol changes.
- [ ] `python3 scripts/audit_publication.py --staged` passes before committing.
- [ ] Exported binaries use `dist/<VERSION>/<platform>/`; no signing files or
      live test logs are included. See [release layout](docs/releases.md).
