/**
 * Relay-side tunnel: authenticate one DSH instance, multiplex HTTP and
 * WebSocket streams, and answer control messages (pairing, devices).
 */

import { randomUUID } from 'node:crypto'
import { WebSocket, WebSocketServer } from 'ws'
import {
  FRAME,
  INITIAL_WINDOW,
  MAX_FRAME_BYTES,
  PROTOCOL_VERSION,
  decodeFrame,
  encodeControl,
  encodeFrame,
  encodeJsonFrame,
} from './protocol.js'
import { RateLimiter } from './rate-limit.js'

const READY_TIMEOUT_MS = 10_000
const CONTROL_TIMEOUT_MS = 10_000
const HEARTBEAT_MS = 25_000
const HEARTBEAT_DEAD_MS = 60_000

/** Hop-by-hop headers that never cross the relay. */
const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

/**
 * Filter headers for forwarding to the plugin. Cookies are stripped because
 * the plugin injects the DSH cookie; the phone's device cookie is relay-only.
 * @param {import('node:http').IncomingHttpHeaders} headers
 * @param {{forWs?: boolean}} [options]
 * @returns {Record<string, string|string[]>}
 */
export function forwardHeaders(headers, { forWs = false } = {}) {
  /** @type {Record<string, string|string[]>} */
  const out = {}
  for (const [rawName, value] of Object.entries(headers)) {
    if (value === undefined) continue
    const name = rawName.toLowerCase()
    if (HOP_BY_HOP.has(name)) continue
    if (name === 'host' || name === 'cookie' || name === 'authorization') continue
    if (name.startsWith('x-dsh-')) continue
    if (forWs && (name.startsWith('sec-websocket-') || name === 'sec-websocket-key'
      || name === 'sec-websocket-version' || name === 'sec-websocket-extensions')) continue
    out[name] = value
  }
  return out
}

/**
 * @param {import('./store.js').DeviceRow} row
 * @returns {{id: string, name: string, createdAt: number, lastSeenAt: number}}
 */
function mapDevice(row) {
  return {
    id: row.id,
    name: row.name,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
  }
}

export class TunnelHub {
  /**
   * @param {{store: import('./store.js').Store, config: import('./config.js').RelayConfig, logger: ReturnType<import('./log.js').createLogger>}} options
   */
  constructor({ store, config, logger }) {
    this.store = store
    this.config = config
    this.logger = logger
    /** @type {Map<string, InstanceTunnel>} */
    this.tunnels = new Map()
    this.wss = new WebSocketServer({
      noServer: true,
      maxPayload: config.maxWsMessageBytes + 1024,
      perMessageDeflate: false,
      clientTracking: false,
    })
    this.authLimiter = new RateLimiter(30)
    this.pairLimiter = new RateLimiter(12)
  }

  /**
   * Handle a `/tunnel` upgrade. On failure the socket is answered and closed.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:stream').Duplex} socket
   * @param {Buffer} head
   */
  handleUpgrade(req, socket, head) {
    const url = new URL(req.url ?? '/', 'http://relay.invalid')
    if (url.pathname !== '/tunnel') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const ip = clientIp(req, this.config.trustProxy)
    if (!this.authLimiter.take(ip)) {
      this.store.audit('instance.rate_limited', { ip })
      socket.write('HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const instanceId = String(req.headers['x-dsh-instance'] ?? url.searchParams.get('instance') ?? '').trim()
    const authorization = req.headers.authorization
    const token = typeof authorization === 'string' && authorization.startsWith('Bearer ')
      ? authorization.slice('Bearer '.length).trim()
      : ''
    if (instanceId === '' || token === '') {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const instance = this.store.authenticateInstance(instanceId, token)
    if (instance === undefined) {
      this.store.audit('instance.auth_failed', { instanceId, ip })
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    this.wss.handleUpgrade(req, socket, head, (ws) => {
      this.attach(ws, instance, ip).catch((error) => {
        this.logger.warn('tunnel attach failed', {
          instanceId: instance.id,
          message: error instanceof Error ? error.message : String(error),
        })
        try { ws.close(1011, 'attach failed') } catch { /* already closed */ }
      })
    })
  }

  /**
   * @param {import('ws').WebSocket} ws
   * @param {import('./store.js').InstanceRow} instance
   * @param {string} ip
   */
  async attach(ws, instance, ip) {
    const tunnel = new InstanceTunnel({
      ws,
      instance,
      hub: this,
      store: this.store,
      config: this.config,
      logger: this.logger,
      ip,
    })
    const previous = this.tunnels.get(instance.id)
    if (previous !== undefined) {
      this.logger.info('replacing an existing tunnel', { instanceId: instance.id })
      previous.close(4001, 'superseded by a new connection')
    }
    this.tunnels.set(instance.id, tunnel)
    try {
      await tunnel.waitReady(READY_TIMEOUT_MS)
      this.store.touchInstance(instance.id)
      this.store.audit('instance.connected', { instanceId: instance.id, ip })
      this.logger.info('instance connected', { instanceId: instance.id, publicHost: instance.public_host })
    } catch (error) {
      this.logger.warn('tunnel did not become ready', {
        instanceId: instance.id,
        message: error instanceof Error ? error.message : String(error),
      })
      tunnel.close(4002, 'handshake failed')
      if (this.tunnels.get(instance.id) === tunnel) this.tunnels.delete(instance.id)
    }
  }

  /**
   * @param {string} instanceId
   * @returns {InstanceTunnel|undefined}
   */
  get(instanceId) {
    return this.tunnels.get(instanceId)
  }

  /**
   * @param {string} instanceId
   * @returns {boolean}
   */
  isOnline(instanceId) {
    return this.tunnels.has(instanceId)
  }

  /**
   * @returns {string[]}
   */
  onlineIds() {
    return [...this.tunnels.keys()]
  }

  /**
   * Notify the plugin that a new device finished pairing.
   * @param {string} instanceId
   * @param {import('./store.js').DeviceRow} device
   */
  notifyDevicePaired(instanceId, device) {
    this.tunnels.get(instanceId)?.sendControl({ type: 'device/paired', device: mapDevice(device) })
  }

  /**
   * @param {string} instanceId
   * @param {string} deviceId
   * @param {string} ip
   */
  notifyDeviceRevoked(instanceId, deviceId, ip) {
    this.store.audit('device.revoked', { instanceId, deviceId, ip })
    this.tunnels.get(instanceId)?.sendControl({ type: 'device/revoked', deviceId })
  }

  async closeAll() {
    const tunnels = [...this.tunnels.values()]
    this.tunnels.clear()
    for (const tunnel of tunnels) tunnel.close(1001, 'relay shutting down')
    await Promise.all(tunnels.map(tunnel => tunnel.waitClosed()))
  }
}

export class InstanceTunnel {
  /**
   * @param {{ws: import('ws').WebSocket, instance: import('./store.js').InstanceRow, hub: TunnelHub, store: import('./store.js').Store, config: import('./config.js').RelayConfig, logger: ReturnType<import('./log.js').createLogger>, ip: string}} options
   */
  constructor({ ws, instance, hub, store, config, logger, ip }) {
    this.ws = ws
    this.instance = instance
    this.hub = hub
    this.store = store
    this.config = config
    this.logger = logger
    this.ip = ip
    this.ready = false
    this.closed = false
    /** @type {Map<number, HttpExchange|WsExchange>} */
    this.streams = new Map()
    this.nextStreamId = 1
    /** @type {Map<string, {resolve: (value: any) => void, reject: (error: Error) => void, timer: NodeJS.Timeout}>} */
    this.pending = new Map()
    this.aliveAt = Date.now()
    /** @type {Promise<void>} */
    this.readyPromise = new Promise((resolve, reject) => {
      this.resolveReady = resolve
      this.rejectReady = reject
    })
    this.readyTimer = setTimeout(() => {
      if (!this.ready) this.rejectReady(new Error('ready timeout'))
    }, READY_TIMEOUT_MS)
    this.readyTimer.unref?.()
    this.closedPromise = new Promise((resolve) => { this.resolveClosed = resolve })

    ws.on('message', (data, isBinary) => {
      try {
        this.handleFrame(Buffer.isBuffer(data) ? data : Buffer.from(data), isBinary)
      } catch (error) {
        this.logger.warn('bad frame from plugin', {
          instanceId: instance.id,
          message: error instanceof Error ? error.message : String(error),
        })
        this.close(4002, 'protocol error')
      }
    })
    ws.on('pong', () => { this.aliveAt = Date.now() })
    ws.on('close', (code, reason) => {
      this.closed = true
      this.ready = false
      clearTimeout(this.readyTimer)
      clearInterval(this.heartbeat)
      for (const stream of this.streams.values()) stream.destroy(new Error('tunnel closed'))
      this.streams.clear()
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer)
        pending.reject(new Error('tunnel closed'))
      }
      this.pending.clear()
      this.rejectReady(new Error('tunnel closed'))
      if (this.hub.tunnels.get(instance.id) === this) this.hub.tunnels.delete(instance.id)
      this.store.audit('instance.disconnected', {
        instanceId: instance.id,
        ip,
        detail: `${String(code)} ${reason.toString('utf8')}`.trim(),
      })
      this.logger.info('instance disconnected', { instanceId: instance.id, code: String(code) })
      this.resolveClosed?.()
    })
    ws.on('error', (error) => {
      this.logger.warn('tunnel socket error', { instanceId: instance.id, message: error.message })
    })

    this.heartbeat = setInterval(() => {
      if (Date.now() - this.aliveAt > HEARTBEAT_DEAD_MS) {
        this.logger.warn('tunnel heartbeat timeout', { instanceId: instance.id })
        this.close(4003, 'heartbeat timeout')
        return
      }
      try { ws.ping() } catch { /* close path owns cleanup */ }
    }, HEARTBEAT_MS)
    this.heartbeat.unref?.()

    this.sendControl({
      type: 'hello',
      protocol: PROTOCOL_VERSION,
      instanceId: instance.id,
      publicHost: instance.public_host,
    })
  }

  /**
   * @param {number} timeoutMs
   * @returns {Promise<void>}
   */
  waitReady(timeoutMs) {
    const timeout = new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error('ready timeout')), timeoutMs)
      timer.unref?.()
      this.readyPromise.finally(() => clearTimeout(timer)).catch(() => {})
    })
    return Promise.race([this.readyPromise, timeout])
  }

  /**
   * @param {object} message
   */
  sendControl(message) {
    if (this.closed || this.ws.readyState !== WebSocket.OPEN) return
    this.ws.send(encodeControl(message))
  }

  /**
   * @param {Buffer} frame
   */
  send(frame) {
    if (this.closed || this.ws.readyState !== WebSocket.OPEN) return
    if (this.ws.bufferedAmount > 8 * 1024 * 1024) {
      this.close(4004, 'send buffer overflow')
      return
    }
    this.ws.send(frame)
  }

  /**
   * @param {object} message
   * @param {number} [timeoutMs]
   * @returns {Promise<any>}
   */
  requestControl(message, timeoutMs = CONTROL_TIMEOUT_MS) {
    if (this.closed || !this.ready) return Promise.reject(new Error('tunnel is not connected'))
    const id = message.id ?? randomUUID()
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`control timeout: ${message.type}`))
      }, timeoutMs)
      timer.unref?.()
      this.pending.set(id, { resolve, reject, timer })
    })
    this.sendControl({ ...message, id })
    return promise
  }

  /**
   * @param {Buffer} data
   * @param {boolean} isBinary
   */
  handleFrame(data, isBinary) {
    const frame = decodeFrame(data)
    if (frame.type === FRAME.CONTROL) {
      this.handleControl(JSON.parse(frame.payload.toString('utf8')))
      return
    }
    if (frame.type === FRAME.PING) {
      this.send(encodeFrame(FRAME.PONG, 0))
      return
    }
    if (frame.type === FRAME.PONG) return
    const stream = this.streams.get(frame.streamId)
    if (stream === undefined) {
      this.send(encodeJsonFrame(FRAME.RESET, frame.streamId, { code: 'unknown-stream', message: 'stream is gone' }))
      return
    }
    stream.onFrame(frame, isBinary)
  }

  /**
   * @param {any} message
   */
  handleControl(message) {
    if (message === null || typeof message !== 'object') return
    if (message.type === 'ready') {
      if (message.protocol !== PROTOCOL_VERSION) {
        this.close(4002, 'protocol version mismatch')
        return
      }
      this.ready = true
      clearTimeout(this.readyTimer)
      this.resolveReady()
      return
    }
    if (message.id !== undefined && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id)
      this.pending.delete(message.id)
      clearTimeout(pending.timer)
      if (String(message.type).endsWith('/error')) pending.reject(new Error(String(message.message ?? 'control failed')))
      else pending.resolve(message)
      return
    }
    if (message.type === 'pair/create') {
      void this.handlePairCreate(message)
      return
    }
    if (message.type === 'devices/list') {
      const devices = this.store.listDevices(this.instance.id).map(mapDevice)
      this.sendControl({ type: 'devices', id: message.id, devices })
      return
    }
    if (message.type === 'device/revoke') {
      this.handleDeviceRevoke(message)
      return
    }
    if (message.type === 'ping') {
      this.sendControl({ type: 'pong', id: message.id })
    }
  }

  /** @param {any} message */
  async handlePairCreate(message) {
    if (!this.hub.pairLimiter.take(this.instance.id)) {
      this.sendControl({ type: 'pair/error', id: message.id, message: 'too many pairing codes; wait a minute' })
      return
    }
    try {
      const ttlMs = Math.min(Math.max(Number(message.ttlMs) || this.config.pairTtlMs, 15_000), 900_000)
      const { code, expiresAt } = this.store.createPairCode(
        this.instance.id,
        ttlMs,
        this.config.pairCodeLength,
      )
      const scheme = new URL(this.config.publicUrl).protocol
      const url = `${scheme}//${this.instance.public_host}/pair?code=${encodeURIComponent(code)}`
      this.store.audit('pair.created', { instanceId: this.instance.id, ip: this.ip })
      this.sendControl({
        type: 'pair/code',
        id: message.id,
        code,
        url,
        expiresAt,
      })
    } catch (error) {
      this.sendControl({
        type: 'pair/error',
        id: message.id,
        message: error instanceof Error ? error.message : String(error),
      })
    }
  }

  /** @param {any} message */
  handleDeviceRevoke(message) {
    const deviceId = typeof message.deviceId === 'string' ? message.deviceId : ''
    const revoked = deviceId !== '' && this.store.revokeDevice(deviceId, this.instance.id)
    if (revoked) this.hub.notifyDeviceRevoked(this.instance.id, deviceId, this.ip)
    const devices = this.store.listDevices(this.instance.id).map(mapDevice)
    this.sendControl({ type: 'devices', id: message.id, devices, revoked })
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  openHttp(req, res) {
    const id = this.nextStreamId
    this.nextStreamId += 1
    const exchange = new HttpExchange({ tunnel: this, id, req, res })
    this.streams.set(id, exchange)
    exchange.start()
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('ws').WebSocket} client
   */
  openWs(req, client) {
    const id = this.nextStreamId
    this.nextStreamId += 1
    const exchange = new WsExchange({ tunnel: this, id, req, client })
    this.streams.set(id, exchange)
    exchange.start()
  }

  /**
   * @param {number} id
   */
  removeStream(id) {
    this.streams.delete(id)
  }

  /**
   * @param {number} code
   * @param {string} reason
   */
  close(code, reason) {
    if (this.closed) return
    this.closed = true
    for (const stream of this.streams.values()) stream.destroy(new Error(reason))
    this.streams.clear()
    try { this.ws.close(code, reason) } catch { /* already closed */ }
  }

  /**
   * Resolve once the underlying WebSocket close handler has finished.
   * @returns {Promise<void>}
   */
  waitClosed() {
    return this.closedPromise ?? Promise.resolve()
  }
}

/** One HTTP request/response over the tunnel. */
class HttpExchange {
  /**
   * @param {{tunnel: InstanceTunnel, id: number, req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse}} options
   */
  constructor({ tunnel, id, req, res }) {
    this.tunnel = tunnel
    this.id = id
    this.req = req
    this.res = res
    this.sendCredit = INITIAL_WINDOW
    this.recvCredit = INITIAL_WINDOW
    this.outgoing = []
    this.outgoingBytes = 0
    this.reqEnded = false
    this.reqPaused = false
    this.responseStarted = false
    this.responseEnded = false
    this.responsePaused = false
    this.requestEndSent = false
    this.destroyed = false
  }

  start() {
    this.req.on('data', chunk => {
      this.outgoing.push(Buffer.from(chunk))
      this.outgoingBytes += chunk.length
      this.pumpOutgoing()
    })
    this.req.on('end', () => {
      this.reqEnded = true
      this.pumpOutgoing()
    })
    this.req.on('error', () => this.destroy(new Error('request error')))
    this.res.on('close', () => {
      if (!this.responseEnded && !this.destroyed) {
        this.tunnel.send(encodeJsonFrame(FRAME.RESET, this.id, {
          code: 'client-closed',
          message: 'client closed the response',
        }))
      }
    })
    this.tunnel.send(encodeJsonFrame(FRAME.OPEN, this.id, {
      kind: 'http',
      method: this.req.method ?? 'GET',
      path: this.req.url ?? '/',
      headers: forwardHeaders(this.req.headers),
    }))
    this.pumpOutgoing()
  }

  /** @param {import('./protocol.js').Frame} frame */
  onFrame(frame) {
    switch (frame.type) {
      case FRAME.HEAD: {
        const head = JSON.parse(frame.payload.toString('utf8'))
        const status = Number(head.status) || 502
        const headers = head.headers ?? {}
        this.responseStarted = true
        try {
          this.res.writeHead(status, cleanResponseHeaders(headers))
        } catch {
          this.destroy(new Error('invalid response headers'))
        }
        break
      }
      case FRAME.DATA:
        this.onData(frame.payload)
        break
      case FRAME.END:
        this.responseEnded = true
        if (!this.res.writableEnded) this.res.end()
        this.cleanup()
        break
      case FRAME.RESET: {
        const payload = frame.payload.length === 0 ? {} : JSON.parse(frame.payload.toString('utf8'))
        this.destroy(new Error(payload.message ?? 'upstream reset'))
        break
      }
      case FRAME.WINDOW: {
        const payload = JSON.parse(frame.payload.toString('utf8'))
        const credit = Number(payload.credit)
        if (Number.isFinite(credit) && credit > 0) {
          this.sendCredit += credit
          this.pumpOutgoing()
        }
        break
      }
      default:
        break
    }
  }

  /** @param {Buffer} payload */
  onData(payload) {
    if (this.responseEnded || this.destroyed) return
    this.recvCredit -= payload.length
    let accepted = false
    try {
      accepted = this.res.write(payload)
    } catch {
      this.destroy(new Error('client closed the response'))
      return
    }
    if (accepted) {
      this.maybeGrantWindow()
      return
    }
    if (!this.responsePaused) {
      this.responsePaused = true
      this.res.once('drain', () => {
        this.responsePaused = false
        this.maybeGrantWindow()
      })
    }
  }

  maybeGrantWindow() {
    if (this.destroyed || this.responsePaused) return
    if (this.recvCredit <= INITIAL_WINDOW / 2) {
      const credit = INITIAL_WINDOW - this.recvCredit
      this.recvCredit = INITIAL_WINDOW
      this.tunnel.send(encodeJsonFrame(FRAME.WINDOW, this.id, { credit }))
    }
  }

  pumpOutgoing() {
    if (this.destroyed || this.requestEndSent) return
    while (this.sendCredit > 0 && this.outgoing.length > 0) {
      const chunk = this.outgoing[0]
      const take = Math.min(chunk.length, this.sendCredit, MAX_FRAME_BYTES)
      const part = chunk.subarray(0, take)
      this.tunnel.send(encodeFrame(FRAME.DATA, this.id, part))
      this.sendCredit -= take
      if (take === chunk.length) this.outgoing.shift()
      else this.outgoing[0] = chunk.subarray(take)
      this.outgoingBytes -= take
    }
    if (this.sendCredit > 0 && this.reqPaused) {
      this.reqPaused = false
      this.req.resume()
    } else if (this.sendCredit <= 0 && !this.reqPaused) {
      this.reqPaused = true
      this.req.pause()
    }
    if (this.reqEnded && this.outgoing.length === 0 && !this.requestEndSent) {
      this.requestEndSent = true
      this.tunnel.send(encodeJsonFrame(FRAME.END, this.id))
    }
  }

  /**
   * @param {Error} error
   */
  destroy(error) {
    if (this.destroyed) return
    this.destroyed = true
    this.cleanup()
    if (!this.res.headersSent) {
      this.res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
    }
    if (!this.res.writableEnded) this.res.end(`relay: ${error.message}`)
  }

  cleanup() {
    this.tunnel.removeStream(this.id)
  }
}

/** One WebSocket over the tunnel. DATA payloads carry a 1-byte flags field. */
class WsExchange {
  /**
   * @param {{tunnel: InstanceTunnel, id: number, req: import('node:http').IncomingMessage, client: import('ws').WebSocket}} options
   */
  constructor({ tunnel, id, req, client }) {
    this.tunnel = tunnel
    this.id = id
    this.req = req
    this.client = client
    this.sendCredit = INITIAL_WINDOW
    this.pending = []
    this.pendingBytes = 0
    this.incoming = []
    this.incomingBytes = 0
    this.upstreamReady = false
    this.closed = false
    this.destroyed = false
  }

  start() {
    this.client.on('message', (data, isBinary) => {
      for (const chunk of splitWsMessage(Buffer.from(data), isBinary)) {
        this.pending.push(chunk)
        this.pendingBytes += chunk.data.length
      }
      if (this.pendingBytes > INITIAL_WINDOW * 2 && !this.client.isPaused) this.client.pause()
      this.pump()
    })
    this.client.on('close', (code, reason) => {
      if (!this.closed) {
        this.closed = true
        this.tunnel.send(encodeJsonFrame(FRAME.CLOSE, this.id, {
          code,
          reason: reason.toString('utf8'),
        }))
      }
      this.cleanup()
    })
    this.client.on('error', () => this.destroy())
    this.tunnel.send(encodeJsonFrame(FRAME.OPEN, this.id, {
      kind: 'ws',
      path: this.req.url ?? '/',
      headers: forwardHeaders(this.req.headers, { forWs: true }),
    }))
  }

  /** @param {import('./protocol.js').Frame} frame */
  onFrame(frame) {
    switch (frame.type) {
      case FRAME.HEAD: {
        const head = JSON.parse(frame.payload.toString('utf8'))
        if (Number(head.status) !== 101) {
          this.client.close(1011, 'upstream refused the WebSocket')
          this.cleanup()
          return
        }
        this.upstreamReady = true
        this.pump()
        break
      }
      case FRAME.DATA: {
        if (frame.payload.length < 1) return
        const flags = frame.payload[0]
        const chunk = frame.payload.subarray(1)
        const binary = (flags & 1) === 1
        const more = (flags & 2) === 2
        if (this.incomingBytes + chunk.length > this.tunnel.config.maxWsMessageBytes) {
          this.destroy()
          return
        }
        this.incoming.push({ chunk, binary })
        this.incomingBytes += chunk.length
        // The chunk has been consumed into the reassembly buffer; grant the
        // credit back immediately so large messages can cross the window.
        this.tunnel.send(encodeJsonFrame(FRAME.WINDOW, this.id, { credit: chunk.length }))
        if (!more) {
          const body = Buffer.concat(this.incoming.map(item => item.chunk))
          const isBinary = this.incoming[0]?.binary === true
          this.incoming = []
          this.incomingBytes = 0
          if (this.client.readyState === WebSocket.OPEN) {
            try {
              this.client.send(body, { binary: isBinary }, (error) => {
                if (error) this.destroy()
              })
            } catch {
              this.destroy()
            }
          }
        }
        break
      }
      case FRAME.CLOSE: {
        const payload = frame.payload.length === 0 ? {} : JSON.parse(frame.payload.toString('utf8'))
        this.closed = true
        this.client.close(Number(payload.code) || 1000, String(payload.reason ?? ''))
        this.cleanup()
        break
      }
      case FRAME.RESET:
        this.destroy()
        break
      case FRAME.WINDOW: {
        const payload = JSON.parse(frame.payload.toString('utf8'))
        const credit = Number(payload.credit)
        if (Number.isFinite(credit) && credit > 0) {
          this.sendCredit += credit
          this.pump()
        }
        break
      }
      default:
        break
    }
  }

  pump() {
    if (this.destroyed || !this.upstreamReady) return
    while (this.sendCredit > 0 && this.pending.length > 0) {
      const item = this.pending[0]
      if (item.data.length > this.sendCredit) break
      this.pending.shift()
      this.pendingBytes -= item.data.length
      const flags = (item.binary ? 1 : 0) | (item.more ? 2 : 0)
      const payload = Buffer.concat([Buffer.from([flags]), item.data])
      this.tunnel.send(encodeFrame(FRAME.DATA, this.id, payload))
      this.sendCredit -= item.data.length
    }
    if (this.pendingBytes < INITIAL_WINDOW / 2 && this.client.isPaused) this.client.resume()
  }

  destroy() {
    if (this.destroyed) return
    this.destroyed = true
    if (this.client.readyState === WebSocket.OPEN) this.client.close(1011, 'relay stream error')
    this.cleanup()
  }

  cleanup() {
    this.tunnel.removeStream(this.id)
  }
}

/**
 * Split one WebSocket message into tunnel-sized fragments.
 * @param {Buffer} data
 * @param {boolean} binary
 * @returns {{data: Buffer, binary: boolean, more: boolean}[]}
 */
function splitWsMessage(data, binary) {
  const chunks = []
  if (data.length === 0) return [{ data, binary, more: false }]
  for (let offset = 0; offset < data.length; offset += MAX_FRAME_BYTES) {
    const end = Math.min(offset + MAX_FRAME_BYTES, data.length)
    chunks.push({ data: data.subarray(offset, end), binary, more: end < data.length })
  }
  return chunks
}

/**
 * @param {Record<string, string|string[]>} headers
 * @returns {Record<string, string|string[]>}
 */
function cleanResponseHeaders(headers) {
  const out = {}
  for (const [name, value] of Object.entries(headers)) {
    const lower = name.toLowerCase()
    if (HOP_BY_HOP.has(lower)) continue
    if (lower === 'transfer-encoding' || lower === 'connection') continue
    if (lower === 'set-cookie') {
      const values = (Array.isArray(value) ? value : [value])
        .filter(cookie => !String(cookie).toLowerCase().startsWith('dsh-auth-'))
      if (values.length === 0) continue
      out[name] = Array.isArray(value) ? values : values[0]
      continue
    }
    out[name] = value
  }
  return out
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {boolean} trustProxy
 * @returns {string}
 */
export function clientIp(req, trustProxy) {
  if (trustProxy) {
    const forwarded = req.headers['x-forwarded-for']
    if (typeof forwarded === 'string' && forwarded !== '') {
      return forwarded.split(',')[0].trim()
    }
  }
  return req.socket.remoteAddress ?? 'unknown'
}
