import './isolated-home.mjs'

/**
 * Route-level test for the in-plugin relay setup form: it writes the token to
 * the 0600 token file, writes non-secret settings, and reconfigures the runtime
 * without a DSH restart.
 */
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Config, defaultTokenFile, resolveConfig, settingsFile } from '../lib/config.js'
import { registerRoutes } from '../lib/routes.js'

const previousHome = process.env.DSH_HOME
const home = mkdtempSync(join(tmpdir(), 'dsh-remote-routes-'))
process.env.DSH_HOME = home

try {
  const rowConfig = Config({ domain: 'mac.relay.example.com' })
  const runtime = {
    settings: resolveConfig(rowConfig),
    next: undefined,
    status() {
      return {
        connected: true,
        mode: 'relay',
        transport: 'relay',
        publicHost: 'mac.relay.example.com',
        relayUrl: this.settings.relayUrl,
        instanceId: this.settings.instanceId,
        devices: [],
        tokenConfigured: this.settings.instanceToken !== undefined,
      }
    },
    async reconfigure(next) {
      this.next = next
      this.settings = next
      return { restartRequired: false }
    },
    async createPairCode() { throw new Error('not used') },
    async revokeDevice() { throw new Error('not used') },
  }

  const handlers = new Map()
  const webCtx = {
    webServer: {
      register(route) {
        handlers.set(route.path, route.handler)
        return () => {}
      },
    },
  }
  const logger = { info() {}, warn() {}, error() {} }
  registerRoutes(webCtx, {
    runtime,
    logger,
    getConfig: () => resolveConfig(rowConfig, logger),
  })
  assert.ok(handlers.has('/remote-tunnel/config'))

  const body = JSON.stringify({
    relayUrl: 'https://relay.example.com',
    instanceId: 'mac',
    instanceToken: 't'.repeat(64),
  })
  const req = (async function* () { yield Buffer.from(body) })()
  req.method = 'POST'
  req.url = '/remote-tunnel/config'
  req.headers = {}
  let statusCode = 0
  let responseBody = ''
  const res = {
    writeHead(status) { statusCode = status },
    end(text) { responseBody = text },
  }
  await handlers.get('/remote-tunnel/config')(req, res)

  assert.equal(statusCode, 200)
  const parsed = JSON.parse(responseBody)
  assert.equal(parsed.ok, true)
  assert.equal(parsed.restartRequired, false)
  assert.equal(parsed.status.connected, true)

  assert.equal(runtime.next.relayUrl, 'wss://relay.example.com/tunnel')
  assert.equal(runtime.next.instanceId, 'mac')
  assert.equal(runtime.next.instanceToken, 't'.repeat(64))
  assert.equal(readFileSync(defaultTokenFile(), 'utf8').trim(), 't'.repeat(64))
  assert.equal(statSync(defaultTokenFile()).mode & 0o777, 0o600)
  const settings = JSON.parse(readFileSync(settingsFile(), 'utf8'))
  assert.equal(settings.relayUrl, 'wss://relay.example.com/tunnel')
  assert.equal(settings.instanceId, 'mac')
  assert.equal(settings.tokenFile, defaultTokenFile())
  assert.equal(statSync(settingsFile()).mode & 0o777, 0o600)
  console.log('routes smoke ok')
} finally {
  if (previousHome === undefined) delete process.env.DSH_HOME
  else process.env.DSH_HOME = previousHome
  rmSync(home, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// One-time enrollment: the plugin generates a code, the relay stores the
// token after the server CLI claims it, and the plugin takes it once.
// ---------------------------------------------------------------------------
{
  const previous = process.env.DSH_HOME
  const dir = mkdtempSync(join(tmpdir(), 'dsh-remote-enroll-'))
  process.env.DSH_HOME = dir
  const { createServer } = await import('node:http')
  let polls = 0
  const relay = createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/api/enroll/start') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, expiresAt: Date.now() + 60_000 }))
      return
    }
    if (req.method === 'GET' && req.url?.startsWith('/api/enroll/')) {
      polls += 1
      res.writeHead(polls === 1 ? 202 : 200, { 'content-type': 'application/json' })
      res.end(JSON.stringify(polls === 1
        ? { ok: true, pending: true }
        : { ok: true, token: 't'.repeat(64), instanceId: 'mac' }))
      return
    }
    res.writeHead(404)
    res.end()
  })
  await new Promise(resolve => relay.listen(0, '127.0.0.1', resolve))
  const relayPort = relay.address().port

  try {
    const rowConfig = Config({
      domain: 'mac.relay.example.com',
      relayUrl: `http://127.0.0.1:${String(relayPort)}`,
      instanceId: 'mac',
    })
    const runtime = {
      settings: resolveConfig(rowConfig),
      next: undefined,
      status() { return { connected: true, mode: 'relay', transport: 'relay', devices: [] } },
      async reconfigure(next) { this.next = next; this.settings = next; return { restartRequired: false } },
      async createPairCode() { throw new Error('not used') },
      async revokeDevice() { throw new Error('not used') },
    }
    const handlers = new Map()
    const webCtx = { webServer: { register(route) { handlers.set(route.path, route.handler); return () => {} } } }
    const logger = { info() {}, warn() {}, error() {} }
    registerRoutes(webCtx, { runtime, logger, getConfig: () => resolveConfig(rowConfig, logger) })

    const call = async (path, method = 'GET', body = undefined) => {
      const req = (async function* () {
        if (body !== undefined) yield Buffer.from(JSON.stringify(body))
      })()
      req.method = method
      req.url = path
      req.headers = {}
      let statusCode = 0
      let text = ''
      const res = { writeHead(status) { statusCode = status }, end(value) { text = value } }
      await handlers.get(path.split('?')[0])(req, res)
      return { statusCode, body: JSON.parse(text) }
    }

    const started = await call('/remote-tunnel/enroll/start', 'POST', {})
    assert.equal(started.statusCode, 200)
    assert.match(started.body.code, /^[A-Z2-9]{10}$/u)
    assert.match(started.body.command, new RegExp(started.body.code))

    const pending = await call(`/remote-tunnel/enroll/poll?code=${started.body.code}`)
    assert.equal(pending.statusCode, 200)
    assert.equal(pending.body.pending, true)

    const imported = await call(`/remote-tunnel/enroll/poll?code=${started.body.code}`)
    assert.equal(imported.statusCode, 200)
    assert.equal(imported.body.imported, true)
    assert.equal(readFileSync(defaultTokenFile(), 'utf8').trim(), 't'.repeat(64))
    assert.equal(runtime.next.instanceToken, 't'.repeat(64))
    assert.equal(runtime.next.instanceId, 'mac')
    assert.equal(statSync(defaultTokenFile()).mode & 0o777, 0o600)
    console.log('enrollment routes ok')
  } finally {
    relay.close()
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    rmSync(dir, { recursive: true, force: true })
  }
}
