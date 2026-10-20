import './isolated-home.mjs'

/**
 * Integration test for the plugin side: a fake DSH HTTP/WS server and a fake
 * relay speaking the real tunnel protocol. It exercises cookie minting, HTTP
 * streaming, WebSocket forwarding, flow-control frames, and control messages.
 */
import assert from 'node:assert/strict'
import http from 'node:http'
import { WebSocketServer } from 'ws'
import { DshProxy } from '../lib/dsh-proxy.js'
import { RelayClient } from '../lib/relay-client.js'
import {
  FRAME,
  PROTOCOL_VERSION,
  decodeFrame,
  encodeControl,
  encodeFrame,
  encodeJsonFrame,
} from '../lib/protocol.js'

const PUBLIC_HOST = 'mac.relay.test'
const TOKEN = 'test-token'
const DSH_COOKIE = 'dsh-auth-test=secret'

/**
 * @param {import('node:http').IncomingMessage} req
 * @returns {string}
 */
function hostOf(req) {
  return String(req.headers.host ?? '')
}

function startDsh() {
  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false })
  const server = http.createServer((req, res) => {
    if (req.url === '/?token=test') {
      assert.equal(hostOf(req), PUBLIC_HOST, 'cookie mint must carry the public Host')
      res.writeHead(303, {
        location: './',
        'set-cookie': `${DSH_COOKIE}; Path=/; HttpOnly; SameSite=Strict`,
      })
      res.end()
      return
    }
    if (req.headers.cookie !== DSH_COOKIE) {
      res.writeHead(401, { 'content-type': 'text/plain' })
      res.end('unauthorized')
      return
    }
    if (hostOf(req) !== PUBLIC_HOST) {
      res.writeHead(400, { 'content-type': 'text/plain' })
      res.end('wrong host')
      return
    }
    if (req.url === '/api/echo' && req.method === 'GET') {
      const body = JSON.stringify({ ok: true, host: hostOf(req), cookie: req.headers.cookie })
      res.writeHead(200, { 'content-type': 'application/json', 'x-test': 'yes' })
      res.end(body)
      return
    }
    if (req.url === '/api/echo' && req.method === 'POST') {
      const chunks = []
      req.on('data', chunk => chunks.push(chunk))
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ body: Buffer.concat(chunks).toString('utf8') }))
      })
      return
    }
    res.writeHead(404)
    res.end('not found')
  })
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/ws') {
      socket.destroy()
      return
    }
    if (req.headers.cookie !== DSH_COOKIE || hostOf(req) !== PUBLIC_HOST) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.on('message', (data, isBinary) => ws.send(data, { binary: isBinary }))
    })
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      resolve({ server, wss, port: address.port, close: () => { wss.close(); server.close() } })
    })
  })
}

class FakeRelay {
  constructor() {
    this.wss = new WebSocketServer({ noServer: true, perMessageDeflate: false })
    this.server = http.createServer()
    this.server.on('upgrade', (req, socket, head) => {
      this.wss.handleUpgrade(req, socket, head, ws => this.attach(ws))
    })
    this.socket = undefined
    this.connected = new Promise((resolve) => { this.resolveConnected = resolve })
    this.streams = new Map()
    this.nextId = 1
    this.controlHandlers = new Map()
  }

  start() {
    return new Promise((resolve) => {
      this.server.listen(0, '127.0.0.1', () => resolve(this.server.address().port))
    })
  }

  attach(ws) {
    this.socket = ws
    ws.on('message', (data) => {
      const frame = decodeFrame(Buffer.from(data))
      if (frame.type === FRAME.CONTROL) {
        const message = JSON.parse(frame.payload.toString('utf8'))
        if (message.type === 'ready') {
          this.resolveConnected()
          return
        }
        const handler = this.controlHandlers.get(message.type)
        if (handler !== undefined) {
          handler(message)
          return
        }
        if (message.type === 'pair/create') {
          this.sendControl({
            type: 'pair/code',
            id: message.id,
            code: 'ABCDEFGHIJ',
            url: 'https://mac.relay.test/pair?code=ABCDEFGHIJ',
            expiresAt: Date.now() + 120_000,
          })
          return
        }
        if (message.type === 'devices/list') {
          this.sendControl({
            type: 'devices',
            id: message.id,
            devices: [{ id: 'd1', name: 'Pixel', createdAt: 1, lastSeenAt: 2 }],
          })
          return
        }
        if (message.type === 'device/revoke') {
          this.sendControl({
            type: 'devices',
            id: message.id,
            devices: [],
            revoked: true,
          })
          return
        }
        return
      }
      if (frame.type === FRAME.WINDOW || frame.type === FRAME.PING) return
      const stream = this.streams.get(frame.streamId)
      if (stream !== undefined) stream.onFrame(frame)
    })
    ws.on('open', () => {})
    this.sendControl({
      type: 'hello',
      protocol: PROTOCOL_VERSION,
      instanceId: 'mac',
      publicHost: PUBLIC_HOST,
    })
  }

  sendControl(message) {
    if (this.socket === undefined) return
    this.socket.send(encodeControl(message))
  }

  send(frame) {
    this.socket.send(frame)
  }

  /**
   * @param {{kind: 'http'|'ws', method?: string, path: string, headers?: object, body?: Buffer}} options
   */
  open({ kind, method = 'GET', path, headers = {}, body = Buffer.alloc(0) }) {
    const id = this.nextId
    this.nextId += 1
    return new Promise((resolve, reject) => {
      const stream = {
        id,
        head: undefined,
        data: [],
        wsData: [],
        onFrame: (frame) => {
          if (frame.type === FRAME.HEAD) {
            stream.head = JSON.parse(frame.payload.toString('utf8'))
            if (kind === 'ws' && stream.head.status === 101) {
              // send one text frame; the fake DSH echoes it
              const text = Buffer.from('hello')
              this.send(encodeFrame(FRAME.DATA, id, Buffer.concat([Buffer.from([0]), text])))
              return
            }
            if (kind === 'http') {
              // request body is sent after OPEN below
            }
          } else if (frame.type === FRAME.DATA) {
            if (kind === 'ws') {
              const flags = frame.payload[0]
              const chunk = frame.payload.subarray(1)
              stream.wsData.push(chunk)
              if ((flags & 2) !== 2) {
                resolve({ head: stream.head, body: Buffer.concat(stream.wsData).toString('utf8') })
                this.streams.delete(id)
              }
            } else {
              stream.data.push(frame.payload)
            }
          } else if (frame.type === FRAME.END) {
            resolve({ head: stream.head, body: Buffer.concat(stream.data).toString('utf8') })
            this.streams.delete(id)
          } else if (frame.type === FRAME.RESET) {
            reject(new Error(frame.payload.toString('utf8')))
            this.streams.delete(id)
          }
        },
      }
      this.streams.set(id, stream)
      this.send(encodeJsonFrame(FRAME.OPEN, id, {
        kind,
        method,
        path,
        headers,
      }))
      if (kind === 'http' && body.length > 0) {
        this.send(encodeFrame(FRAME.DATA, id, body))
        this.send(encodeJsonFrame(FRAME.END, id))
      } else if (kind === 'http') {
        this.send(encodeJsonFrame(FRAME.END, id))
      }
    })
  }

  close() {
    this.wss.close()
    this.server.close()
  }
}

const dsh = await startDsh()
const proxy = new DshProxy({
  localHost: '127.0.0.1',
  localPort: dsh.port,
  publicHost: PUBLIC_HOST,
  getTokenUrl: () => `http://127.0.0.1:${String(dsh.port)}/?token=test`,
  logger: { info: () => {}, warn: () => {} },
})
const relay = new FakeRelay()
const relayPort = await relay.start()
const client = new RelayClient({
  url: `ws://127.0.0.1:${String(relayPort)}/tunnel`,
  instanceId: 'mac',
  instanceToken: TOKEN,
  publicHost: PUBLIC_HOST,
  proxy,
  logger: { info: () => {}, warn: () => {} },
})
client.start()
await Promise.race([
  relay.connected,
  new Promise((_, reject) => setTimeout(() => reject(new Error('relay handshake timed out')), 3000)),
])
await new Promise(resolve => setTimeout(resolve, 20))

const devices = await client.listDevices()
assert.equal(devices.length, 1)
assert.equal(devices[0].name, 'Pixel')

const pair = await client.createPairCode()
assert.equal(pair.code, 'ABCDEFGHIJ')
assert.match(pair.url, /^https:\/\/mac\.relay\.test\/pair\?code=/)

const get = await relay.open({ kind: 'http', method: 'GET', path: '/api/echo' })
assert.equal(get.head.status, 200)
assert.equal(JSON.parse(get.body).host, PUBLIC_HOST)
assert.equal(JSON.parse(get.body).cookie, DSH_COOKIE)

const post = await relay.open({
  kind: 'http',
  method: 'POST',
  path: '/api/echo',
  headers: { 'content-type': 'text/plain', 'content-length': '5' },
  body: Buffer.from('hello'),
})
assert.equal(post.head.status, 200)
assert.equal(JSON.parse(post.body).body, 'hello')

const wsResult = await relay.open({ kind: 'ws', path: '/ws' })
assert.equal(wsResult.head.status, 101)
assert.equal(wsResult.body, 'hello')

const remaining = await client.revokeDevice('d1')
assert.deepEqual(remaining, [])

client.stop()
relay.close()
proxy.close()
dsh.close()
console.log('relay integration ok')
