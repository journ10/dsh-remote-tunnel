/**
 * Plugin-side relay client: one outbound WSS, multiplexed HTTP/WebSocket
 * streams, control messages for pairing and devices, and reconnect.
 */

import { randomUUID } from 'node:crypto'
import { WebSocket } from 'ws'
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

const MIN_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 30_000
const STABLE_AFTER_MS = 10_000
const CONTROL_TIMEOUT_MS = 10_000
const READY_TIMEOUT_MS = 12_000

/**
 * @param {unknown} error
 * @returns {string}
 */
function messageOf(error) {
  return error instanceof Error ? error.message : String(error)
}

/**
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

export class RelayClient {
  /**
   * @param {{url: string, instanceId: string, instanceToken: string, publicHost: string, proxy: import('./dsh-proxy.js').DshProxy, logger: any, maxWsMessageBytes?: number, onState?: (state: object) => void}} options
   */
  constructor({ url, instanceId, instanceToken, publicHost, proxy, logger, maxWsMessageBytes = 8 * 1024 * 1024, onState }) {
    this.url = url
    this.instanceId = instanceId
    this.instanceToken = instanceToken
    this.publicHost = publicHost
    this.proxy = proxy
    this.logger = logger
    this.maxWsMessageBytes = maxWsMessageBytes
    this.onState = onState
    this.stopped = false
    this.connected = false
    this.ws = undefined
    this.retryTimer
    this.readyTimer
    this.backoff = MIN_BACKOFF_MS
    this.connectedAt = 0
    this.stableTimer
    this.lastError = ''
    /** @type {Map<number, HttpStream|WsStream>} */
    this.streams = new Map()
    /** @type {Map<string, {resolve: (value: any) => void, reject: (error: Error) => void, timer: NodeJS.Timeout}>} */
    this.pending = new Map()
    this.devices = []
    this.lastConnectedAt = 0
  }

  start() {
    this.stopped = false
    this.connect()
  }

  stop() {
    this.stopped = true
    clearTimeout(this.retryTimer)
    clearTimeout(this.readyTimer)
    clearTimeout(this.stableTimer)
    this.retryTimer = undefined
    this.readyTimer = undefined
    this.cleanupConnection(new Error('relay client stopped'))
    if (this.ws !== undefined) {
      try { this.ws.close(1000, 'plugin stopping') } catch { /* already closed */ }
      this.ws = undefined
    }
    this.connected = false
    this.emitState()
  }

  emitState() {
    this.onState?.(this.status())
  }

  status() {
    return {
      connected: this.connected,
      instanceId: this.instanceId,
      relayUrl: this.url,
      publicHost: this.publicHost,
      lastError: this.lastError,
      lastConnectedAt: this.lastConnectedAt,
      devices: this.devices,
      streams: this.streams.size,
    }
  }

  connect() {
    if (this.stopped) return
    this.lastError = ''
    this.emitState()
    const ws = new WebSocket(this.url, {
      headers: {
        authorization: `Bearer ${this.instanceToken}`,
        'x-dsh-instance': this.instanceId,
      },
      perMessageDeflate: false,
      maxPayload: this.maxWsMessageBytes + 1024,
      handshakeTimeout: 15_000,
    })
    this.ws = ws
    let opened = false
    ws.on('open', () => {
      opened = true
      this.connectedAt = Date.now()
      this.logger.info('dsh-remote-tunnel: relay WebSocket open; waiting for hello')
      this.readyTimer = setTimeout(() => {
        if (!this.connected) {
          this.lastError = 'relay did not complete the handshake'
          this.logger.warn(`dsh-remote-tunnel: ${this.lastError}`)
          ws.close(4002, 'ready timeout')
        }
      }, READY_TIMEOUT_MS)
      this.readyTimer.unref?.()
    })
    ws.on('message', (data, isBinary) => {
      try {
        this.handleFrame(Buffer.isBuffer(data) ? data : Buffer.from(data), isBinary)
      } catch (error) {
        this.lastError = `bad frame from relay: ${messageOf(error)}`
        this.logger.warn(`dsh-remote-tunnel: ${this.lastError}`)
        ws.close(4002, 'protocol error')
      }
    })
    ws.on('close', (code, reason) => {
      clearTimeout(this.readyTimer)
      this.readyTimer = undefined
      const wasConnected = this.connected
      this.connected = false
      this.cleanupConnection(new Error(`relay connection closed (${String(code)})`))
      if (this.ws === ws) this.ws = undefined
      if (this.stopped) return
      if (wasConnected) {
        this.logger.warn(`dsh-remote-tunnel: relay disconnected (${String(code)} ${reason.toString('utf8')})`)
      }
      this.lastError = `relay disconnected (${String(code)})`
      this.scheduleReconnect()
    })
    ws.on('error', (error) => {
      this.lastError = messageOf(error)
      this.logger.warn(`dsh-remote-tunnel: relay socket error: ${this.lastError}`)
    })
    ws.on('unexpected-response', (req, res) => {
      this.lastError = `relay rejected the connection (HTTP ${String(res.statusCode ?? 0)})`
      this.logger.warn(`dsh-remote-tunnel: ${this.lastError}`)
      res.resume()
      req.destroy()
      if (!opened) {
        if (this.ws === ws) this.ws = undefined
        this.scheduleReconnect()
      }
    })
  }

  scheduleReconnect() {
    if (this.stopped) return
    if (this.retryTimer !== undefined) return
    const delay = Math.round(this.backoff * (0.8 + Math.random() * 0.4))
    this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS)
    this.logger.warn(`dsh-remote-tunnel: reconnecting in ${String(delay)}ms`)
    this.emitState()
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined
      this.connect()
    }, delay)
    this.retryTimer.unref?.()
  }

  /**
   * @param {Error} error
   */
  cleanupConnection(error) {
    for (const stream of this.streams.values()) stream.destroy(error)
    this.streams.clear()
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
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
    if (frame.type === FRAME.OPEN) {
      this.handleOpen(frame.streamId, JSON.parse(frame.payload.toString('utf8')))
      return
    }
    if (frame.type === FRAME.PING) {
      this.send(encodeFrame(FRAME.PONG, 0))
      return
    }
    if (frame.type === FRAME.PONG) return
    const stream = this.streams.get(frame.streamId)
    if (stream === undefined) return
    stream.onFrame(frame, isBinary)
  }

  /** @param {any} message */
  handleControl(message) {
    if (message === null || typeof message !== 'object') return
    if (message.type === 'hello') {
      if (message.protocol !== PROTOCOL_VERSION) {
        this.lastError = `protocol mismatch (${String(message.protocol)})`
        this.logger.warn(`dsh-remote-tunnel: ${this.lastError}`)
        this.ws?.close(4002, 'protocol mismatch')
        return
      }
      clearTimeout(this.readyTimer)
      this.readyTimer = undefined
      this.sendControl({ type: 'ready', protocol: PROTOCOL_VERSION })
      this.connected = true
      this.lastConnectedAt = Date.now()
      clearTimeout(this.stableTimer)
      this.stableTimer = setTimeout(() => {
        if (this.connected) this.backoff = MIN_BACKOFF_MS
      }, STABLE_AFTER_MS)
      this.stableTimer.unref?.()
      this.lastError = ''
      this.logger.info(`dsh-remote-tunnel: relay ready at ${this.publicHost}`)
      this.emitState()
      void this.listDevices().then((devices) => {
        this.devices = devices
        this.emitState()
      }).catch(() => undefined)
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
    if (message.type === 'device/paired' && message.device !== undefined) {
      this.devices = [message.device, ...this.devices.filter(device => device.id !== message.device.id)]
      this.emitState()
      return
    }
    if (message.type === 'device/revoked' && typeof message.deviceId === 'string') {
      this.devices = this.devices.filter(device => device.id !== message.deviceId)
      this.emitState()
    }
  }

  /**
   * @param {number} streamId
   * @param {any} open
   */
  handleOpen(streamId, open) {
    if (this.streams.has(streamId)) {
      this.send(encodeJsonFrame(FRAME.RESET, streamId, { code: 'duplicate', message: 'stream already exists' }))
      return
    }
    if (open?.kind === 'http') {
      const stream = new HttpStream({ client: this, id: streamId, open })
      this.streams.set(streamId, stream)
      stream.start()
      return
    }
    if (open?.kind === 'ws') {
      const stream = new WsStream({ client: this, id: streamId, open })
      this.streams.set(streamId, stream)
      stream.start()
      return
    }
    this.send(encodeJsonFrame(FRAME.RESET, streamId, { code: 'bad-kind', message: 'unknown stream kind' }))
  }

  /**
   * @param {object} message
   * @param {number} [timeoutMs]
   * @returns {Promise<any>}
   */
  requestControl(message, timeoutMs = CONTROL_TIMEOUT_MS) {
    if (!this.connected || this.ws === undefined || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('relay is not connected'))
    }
    const id = randomUUID()
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

  /** @param {object} message */
  sendControl(message) {
    if (this.ws === undefined || this.ws.readyState !== WebSocket.OPEN) return
    this.ws.send(encodeControl(message))
  }

  /** @param {Buffer} frame */
  send(frame) {
    if (this.ws === undefined || this.ws.readyState !== WebSocket.OPEN) return
    if (this.ws.bufferedAmount > 8 * 1024 * 1024) {
      this.lastError = 'relay send buffer overflow'
      this.ws.close(4004, 'send buffer overflow')
      return
    }
    this.ws.send(frame)
  }

  /**
   * @param {number} [ttlMs]
   * @returns {Promise<{url: string, code: string, expiresAt: number}>}
   */
  async createPairCode(ttlMs = 120_000) {
    const response = await this.requestControl({ type: 'pair/create', ttlMs })
    return {
      url: String(response.url),
      code: String(response.code),
      expiresAt: Number(response.expiresAt),
    }
  }

  /** @returns {Promise<object[]>} */
  async listDevices() {
    const response = await this.requestControl({ type: 'devices/list' })
    return Array.isArray(response.devices) ? response.devices : []
  }

  /**
   * @param {string} deviceId
   * @returns {Promise<object[]>}
   */
  async revokeDevice(deviceId) {
    const response = await this.requestControl({ type: 'device/revoke', deviceId })
    this.devices = Array.isArray(response.devices) ? response.devices : this.devices
    this.emitState()
    return this.devices
  }

  /**
   * @param {number} id
   */
  removeStream(id) {
    this.streams.delete(id)
  }
}

class HttpStream {
  /**
   * @param {{client: RelayClient, id: number, open: any}} options
   */
  constructor({ client, id, open }) {
    this.client = client
    this.id = id
    this.open = open
    this.req = undefined
    this.res = undefined
    this.pendingBody = []
    this.bodyEnded = false
    this.reqEnded = false
    this.sendCredit = INITIAL_WINDOW
    this.recvCredit = INITIAL_WINDOW
    this.outgoing = []
    this.outgoingBytes = 0
    this.responseEndSent = false
    this.resEnded = false
    this.destroyed = false
    this.requestReady = false
  }

  start() {
    this.client.proxy.request({
      method: this.open.method,
      path: this.open.path,
      headers: this.open.headers ?? {},
      onResponse: res => this.onResponse(res),
      onError: error => this.destroy(error),
    }).then((req) => {
      this.req = req
      this.requestReady = true
      this.flushBody()
    }).catch(error => this.destroy(error))
  }

  /** @param {import('./protocol.js').Frame} frame */
  onFrame(frame) {
    switch (frame.type) {
      case FRAME.DATA:
        this.onRequestBody(frame.payload)
        break
      case FRAME.END:
        this.bodyEnded = true
        this.reqEnded = true
        this.flushBody()
        break
      case FRAME.RESET:
        this.destroy(new Error('relay reset'))
        break
      case FRAME.WINDOW: {
        const payload = JSON.parse(frame.payload.toString('utf8'))
        const credit = Number(payload.credit)
        if (Number.isFinite(credit) && credit > 0) {
          this.sendCredit += credit
          this.pumpResponse()
        }
        break
      }
      default:
        break
    }
  }

  /** @param {Buffer} chunk */
  onRequestBody(chunk) {
    this.pendingBody.push(Buffer.from(chunk))
    this.recvCredit -= chunk.length
    this.flushBody()
  }

  flushBody() {
    if (!this.requestReady || this.req === undefined || this.destroyed) return
    while (this.pendingBody.length > 0) {
      const chunk = this.pendingBody[0]
      const accepted = this.req.write(chunk)
      this.pendingBody.shift()
      if (!accepted) {
        this.req.once('drain', () => {
          this.maybeGrantRequestWindow()
          this.flushBody()
        })
        return
      }
      this.maybeGrantRequestWindow()
    }
    if (this.reqEnded && !this.req.writableEnded) this.req.end()
  }

  maybeGrantRequestWindow() {
    if (this.destroyed || this.recvCredit > INITIAL_WINDOW / 2) return
    const credit = INITIAL_WINDOW - this.recvCredit
    this.recvCredit = INITIAL_WINDOW
    this.client.send(encodeJsonFrame(FRAME.WINDOW, this.id, { credit }))
  }

  /** @param {import('node:http').IncomingMessage} res */
  onResponse(res) {
    if (this.destroyed) { res.resume(); return }
    this.res = res
    const headers = { ...res.headers }
    if (headers['set-cookie'] !== undefined) {
      const values = (Array.isArray(headers['set-cookie']) ? headers['set-cookie'] : [headers['set-cookie']])
        .filter(cookie => !String(cookie).toLowerCase().startsWith('dsh-auth-'))
      if (values.length === 0) delete headers['set-cookie']
      else headers['set-cookie'] = Array.isArray(headers['set-cookie']) ? values : values[0]
    }
    this.client.send(encodeJsonFrame(FRAME.HEAD, this.id, {
      status: res.statusCode ?? 502,
      headers,
    }))
    res.on('data', chunk => {
      this.outgoing.push(Buffer.from(chunk))
      this.outgoingBytes += chunk.length
      this.pumpResponse()
    })
    res.on('end', () => {
      this.resEnded = true
      this.pumpResponse()
    })
    res.on('error', error => this.destroy(error))
  }

  pumpResponse() {
    if (this.destroyed) return
    while (this.sendCredit > 0 && this.outgoing.length > 0) {
      const chunk = this.outgoing[0]
      const take = Math.min(chunk.length, this.sendCredit, MAX_FRAME_BYTES)
      this.client.send(encodeFrame(FRAME.DATA, this.id, chunk.subarray(0, take)))
      this.sendCredit -= take
      this.outgoingBytes -= take
      if (take === chunk.length) this.outgoing.shift()
      else this.outgoing[0] = chunk.subarray(take)
    }
    if (this.resEnded && this.outgoing.length === 0 && !this.responseEndSent) {
      this.responseEndSent = true
      this.client.send(encodeJsonFrame(FRAME.END, this.id))
      this.cleanup()
    }
  }

  /** @param {Error} error */
  destroy(error) {
    if (this.destroyed) return
    this.destroyed = true
    try { this.req?.destroy() } catch { /* best effort */ }
    try { this.res?.destroy() } catch { /* best effort */ }
    this.client.send(encodeJsonFrame(FRAME.RESET, this.id, {
      code: 'upstream-error',
      message: messageOf(error),
    }))
    this.cleanup()
  }

  cleanup() {
    this.client.removeStream(this.id)
  }
}

class WsStream {
  /**
   * @param {{client: RelayClient, id: number, open: any}} options
   */
  constructor({ client, id, open }) {
    this.client = client
    this.id = id
    this.open = open
    this.upstream = undefined
    this.upstreamReady = false
    this.pending = []
    this.incoming = []
    this.incomingBytes = 0
    this.sendCredit = INITIAL_WINDOW
    this.destroyed = false
    this.closed = false
  }

  start() {
    this.client.proxy.webSocket({
      path: this.open.path,
      headers: this.open.headers ?? {},
    }).then((upstream) => {
      if (this.destroyed) { upstream.terminate(); return }
      this.upstream = upstream
      this.upstreamReady = true
      this.client.send(encodeJsonFrame(FRAME.HEAD, this.id, { status: 101, headers: {} }))
      upstream.on('message', (data, isBinary) => this.enqueueMessage(Buffer.from(data), isBinary))
      upstream.on('close', (code, reason) => {
        if (this.closed) return
        this.closed = true
        this.client.send(encodeJsonFrame(FRAME.CLOSE, this.id, {
          code,
          reason: reason.toString('utf8'),
        }))
        this.cleanup()
      })
      upstream.on('error', error => this.destroy(error))
      this.pump()
    }).catch(error => this.destroy(error))
  }

  /** @param {import('./protocol.js').Frame} frame */
  onFrame(frame, isBinary) {
    switch (frame.type) {
      case FRAME.DATA: {
        if (frame.payload.length < 1) return
        const flags = frame.payload[0]
        const chunk = frame.payload.subarray(1)
        const more = (flags & 2) === 2
        if (this.incomingBytes + chunk.length > this.client.maxWsMessageBytes) {
          this.destroy(new Error('WebSocket message too large'))
          return
        }
        this.incoming.push({ chunk, binary: (flags & 1) === 1 })
        this.incomingBytes += chunk.length
        this.client.send(encodeJsonFrame(FRAME.WINDOW, this.id, { credit: chunk.length }))
        if (!more) {
          const body = Buffer.concat(this.incoming.map(item => item.chunk))
          const binary = this.incoming[0]?.binary === true
          this.incoming = []
          this.incomingBytes = 0
          this.deliver(body, binary)
        }
        break
      }
      case FRAME.CLOSE: {
        const payload = frame.payload.length === 0 ? {} : JSON.parse(frame.payload.toString('utf8'))
        this.closed = true
        this.upstream?.close(Number(payload.code) || 1000, String(payload.reason ?? ''))
        this.cleanup()
        break
      }
      case FRAME.RESET:
        this.destroy(new Error('relay reset'))
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

  /** @param {Buffer} body @param {boolean} binary */
  deliver(body, binary) {
    if (!this.upstreamReady || this.upstream === undefined) {
      this.pending.push({ body, binary })
      return
    }
    this.upstream.send(body, { binary }, (error) => {
      if (error) this.destroy(error)
    })
  }

  /** @param {Buffer} data @param {boolean} binary */
  enqueueMessage(data, binary) {
    for (const item of splitWsMessage(data, binary)) {
      this.pending.push({ chunk: item.data, binary: item.binary, more: item.more })
    }
    this.pump()
  }

  pump() {
    if (this.destroyed || !this.upstreamReady) return
    while (this.sendCredit > 0 && this.pending.length > 0) {
      const item = this.pending[0]
      if (item.body !== undefined) {
        // A complete message waiting for the upstream socket to open.
        this.pending.shift()
        this.deliver(item.body, item.binary)
        continue
      }
      if (item.chunk.length > this.sendCredit) break
      this.pending.shift()
      const flags = (item.binary ? 1 : 0) | (item.more ? 2 : 0)
      const payload = Buffer.concat([Buffer.from([flags]), item.chunk])
      this.client.send(encodeFrame(FRAME.DATA, this.id, payload))
      this.sendCredit -= item.chunk.length
    }
  }

  /** @param {Error} error */
  destroy(error) {
    if (this.destroyed) return
    this.destroyed = true
    if (!this.closed) {
      this.client.send(encodeJsonFrame(FRAME.RESET, this.id, {
        code: 'upstream-error',
        message: messageOf(error),
      }))
    }
    try { this.upstream?.terminate() } catch { /* best effort */ }
    this.cleanup()
  }

  cleanup() {
    this.client.removeStream(this.id)
  }
}
