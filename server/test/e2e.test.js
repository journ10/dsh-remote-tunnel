/**
 * End-to-end relay test: a real relay process, a fake DSH tunnel peer, pairing,
 * device cookies, HTTP/WS proxying, and revocation.
 */
import assert from 'node:assert/strict'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import test from 'node:test'
import { WebSocket } from 'ws'
import { loadConfig } from '../src/config.js'
import { createRelay } from '../src/index.js'
import { Store } from '../src/store.js'
import {
  FRAME,
  PROTOCOL_VERSION,
  decodeFrame,
  encodeControl,
  encodeFrame,
  encodeJsonFrame,
} from '../src/protocol.js'

const PUBLIC_HOST = 'relay.test'
const INSTANCE_HOST = 'default.relay.test'
const TOKEN = 'test-instance-token'

class FakePlugin {
  constructor(url) {
    this.url = url
    this.ws = undefined
    this.pending = new Map()
    this.wsStreams = new Set()
  }

  connect() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url, {
        headers: { authorization: `Bearer ${TOKEN}`, 'x-dsh-instance': 'default' },
        perMessageDeflate: false,
      })
      this.ws = ws
      ws.once('error', reject)
      ws.on('message', (data) => {
        const frame = decodeFrame(Buffer.from(data))
        if (frame.type === FRAME.CONTROL) {
          const message = JSON.parse(frame.payload.toString('utf8'))
          if (message.type === 'hello') {
            this.sendControl({ type: 'ready', protocol: PROTOCOL_VERSION })
            resolve()
            return
          }
          if (message.id !== undefined && this.pending.has(message.id)) {
            const pending = this.pending.get(message.id)
            this.pending.delete(message.id)
            if (String(message.type).endsWith('/error')) pending.reject(new Error(message.message))
            else pending.resolve(message)
          }
          return
        }
        if (frame.type === FRAME.OPEN) {
          this.handleOpen(frame.streamId, JSON.parse(frame.payload.toString('utf8')))
          return
        }
        if (frame.type === FRAME.DATA && this.wsStreams.has(frame.streamId)) {
          this.send(encodeFrame(FRAME.DATA, frame.streamId, frame.payload))
          return
        }
      })
    })
  }

  handleOpen(id, open) {
    if (open.kind === 'ws') {
      this.wsStreams.add(id)
      this.send(encodeJsonFrame(FRAME.HEAD, id, { status: 101, headers: {} }))
      return
    }
    this.send(encodeJsonFrame(FRAME.HEAD, id, {
      status: 200,
      headers: { 'content-type': 'text/plain', 'x-path': open.path },
    }))
    this.send(encodeFrame(FRAME.DATA, id, Buffer.from(`hello:${open.path}`)))
    this.send(encodeJsonFrame(FRAME.END, id))
  }

  send(frame) {
    this.ws.send(frame)
  }

  sendControl(message) {
    this.ws.send(encodeControl(message))
  }

  requestControl(message) {
    return new Promise((resolve, reject) => {
      const id = randomUUID()
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error('control timeout'))
      }, 3000)
      this.pending.set(id, {
        resolve: (value) => { clearTimeout(timer); resolve(value) },
        reject: (error) => { clearTimeout(timer); reject(error) },
      })
      this.sendControl({ ...message, id })
    })
  }

  close() {
    this.ws?.close()
  }
}

function httpRequest(port, { path, method = 'GET', headers = {}, body = undefined }) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1',
      port,
      path,
      method,
      headers: { Host: INSTANCE_HOST, ...headers },
    }, (res) => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }))
    })
    req.on('error', reject)
    if (body !== undefined) req.write(body)
    req.end()
  })
}

test('relay pairing, HTTP/WS proxy, and device revocation', async (t) => {
  const config = loadConfig({
    PUBLIC_HOST,
    INSTANCE_BASE_DOMAIN: PUBLIC_HOST,
    PUBLIC_URL: `http://${PUBLIC_HOST}`,
    PORT: '1',
    COOKIE_SECRET: '0123456789abcdef0123456789abcdef',
    BOOTSTRAP_INSTANCE_ID: 'default',
    BOOTSTRAP_INSTANCE_TOKEN: TOKEN,
    COOKIE_SECURE: 'false',
    DATA_DIR: './unused',
  })
  const relay = createRelay({
    config,
    logger: { debug() {}, info() {}, warn() {}, error() {} },
    store: new Store(':memory:'),
  })
  await new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve))
  const port = relay.server.address().port
  t.after(async () => {
    plugin.close()
    await relay.close()
  })

  const plugin = new FakePlugin(`ws://127.0.0.1:${String(port)}/tunnel`)
  await plugin.connect()
  for (let i = 0; i < 50 && !relay.hub.isOnline('default'); i += 1) {
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  assert.equal(relay.hub.isOnline('default'), true)

  const health = await httpRequest(port, { path: '/healthz', headers: { Host: PUBLIC_HOST } })
  assert.equal(health.status, 200)
  assert.equal(JSON.parse(health.body).ok, true)

  const ask = await httpRequest(port, {
    path: `/internal/tls/ask?domain=${INSTANCE_HOST}`,
    headers: { Host: PUBLIC_HOST },
  })
  assert.equal(ask.status, 200)

  // Caddy asks with the internal upstream Host, and Docker health checks use
  // 127.0.0.1:8080. Both must work without the public hostname.
  const internalAsk = await httpRequest(port, {
    path: `/internal/tls/ask?domain=${INSTANCE_HOST}`,
    headers: { Host: 'relay:8080' },
  })
  assert.equal(internalAsk.status, 200)
  const internalHealth = await httpRequest(port, {
    path: '/healthz',
    headers: { Host: `127.0.0.1:${String(port)}` },
  })
  assert.equal(internalHealth.status, 200)


  const enrollCode = 'ABCDEFGHJK'
  const enrollStart = await httpRequest(port, {
    path: '/api/enroll/start',
    method: 'POST',
    headers: { Host: PUBLIC_HOST, 'content-type': 'application/json' },
    body: JSON.stringify({ code: enrollCode, instanceId: 'default' }),
  })
  assert.equal(enrollStart.status, 200)
  const enrollPending = await httpRequest(port, {
    path: `/api/enroll/${enrollCode}`,
    headers: { Host: PUBLIC_HOST },
  })
  assert.equal(enrollPending.status, 202)
  const enrollClaim = await httpRequest(port, {
    path: '/api/enroll/claim',
    method: 'POST',
    headers: { Host: '127.0.0.1', 'content-type': 'application/json' },
    body: JSON.stringify({ code: enrollCode, token: TOKEN }),
  })
  assert.equal(enrollClaim.status, 200)
  const enrollTake = await httpRequest(port, {
    path: `/api/enroll/${enrollCode}`,
    headers: { Host: PUBLIC_HOST },
  })
  assert.equal(enrollTake.status, 200)
  assert.equal(JSON.parse(enrollTake.body).token, TOKEN)
  const enrollAgain = await httpRequest(port, {
    path: `/api/enroll/${enrollCode}`,
    headers: { Host: PUBLIC_HOST },
  })
  assert.equal(enrollAgain.status, 404)

  const pair = await plugin.requestControl({ type: 'pair/create', ttlMs: 60_000 })
  assert.match(pair.url, /^http:\/\/default\.relay\.test\/pair\?code=/)
  assert.equal(pair.code.length, 10)

  const paired = await httpRequest(port, { path: `/pair?code=${pair.code}`, headers: { 'user-agent': 'test' } })
  assert.equal(paired.status, 303)
  const setCookie = Array.isArray(paired.headers['set-cookie'])
    ? paired.headers['set-cookie'][0]
    : paired.headers['set-cookie']
  assert.ok(setCookie.startsWith('__dsh_device='))
  const cookie = setCookie.split(';')[0]

  const unauthorized = await httpRequest(port, { path: '/hello' })
  assert.equal(unauthorized.status, 401)

  const proxied = await httpRequest(port, { path: '/hello', headers: { Cookie: cookie } })
  assert.equal(proxied.status, 200)
  assert.equal(proxied.body, 'hello:/hello')
  assert.equal(proxied.headers['x-path'], '/hello')

  const post = await httpRequest(port, {
    path: '/submit',
    method: 'POST',
    headers: { Cookie: cookie, 'content-type': 'text/plain' },
    body: 'payload',
  })
  assert.equal(post.status, 200)
  assert.equal(post.body, 'hello:/submit')

  const ws = new WebSocket(`ws://127.0.0.1:${String(port)}/ws`, {
    headers: { host: INSTANCE_HOST, cookie },
  })
  await new Promise((resolve, reject) => {
    ws.once('open', resolve)
    ws.once('error', reject)
  })
  const echoed = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('ws echo timeout')), 3000)
    ws.once('message', (data, isBinary) => {
      clearTimeout(timer)
      resolve({ data: data.toString(), isBinary })
    })
    ws.send('ping')
  })
  assert.equal(echoed.data, 'ping')
  assert.equal(echoed.isBinary, false)
  ws.close()

  const devices = relay.store.listDevices('default')
  assert.equal(devices.length, 1)
  const revoked = await plugin.requestControl({ type: 'device/revoke', deviceId: devices[0].id })
  assert.equal(revoked.revoked, true)
  assert.deepEqual(revoked.devices, [])

  const afterRevoke = await httpRequest(port, { path: '/hello', headers: { Cookie: cookie } })
  assert.equal(afterRevoke.status, 401)

})
