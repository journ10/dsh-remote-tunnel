/** HTTP routing, pairing, device authentication, and instance proxying. */

import { WebSocketServer } from 'ws'
import {
  DEVICE_COOKIE,
  createDeviceCookie,
  parseCookies,
  serializeDeviceCookie,
  verifyDeviceCookie,
} from './cookie.js'
import {
  errorPage,
  landingPage,
  notFoundPage,
  offlinePage,
  pairErrorPage,
  unauthorizedPage,
} from './pages.js'
import { EnrollmentError } from './enroll.js'
import { RateLimiter } from './rate-limit.js'
import { clientIp } from './tunnel.js'

const INSTANCE_ID = /^[a-z0-9][a-z0-9-]{0,31}$/u

/**
 * @param {string|undefined} header
 * @returns {string|undefined}
 */
export function parseHost(header) {
  if (header === undefined || header === '') return undefined
  let host = header.trim().toLowerCase()
  if (host.startsWith('[')) return undefined // IPv6 literals are not instance hosts
  const colon = host.lastIndexOf(':')
  if (colon !== -1) {
    const port = host.slice(colon + 1)
    if (!/^\d+$/u.test(port)) return undefined
    host = host.slice(0, colon)
  }
  if (host === '') return undefined
  return host
}

/**
 * @param {string|undefined} value
 * @returns {string}
 */
export function sanitizeDeviceName(value) {
  const fallback = 'Mobile device'
  if (value === undefined) return fallback
  const cleaned = value
    .replaceAll(/[\u0000-\u001f\u007f]/gu, ' ')
    .replaceAll(/\s+/gu, ' ')
    .trim()
    .slice(0, 64)
  return cleaned === '' ? fallback : cleaned
}

/**
 * @param {string|undefined} userAgent
 * @returns {string}
 */
function deviceNameFromUserAgent(userAgent) {
  if (userAgent === undefined) return 'Mobile device'
  if (/android/iu.test(userAgent)) return 'Android device'
  if (/(iphone|ipad|ipod)/iu.test(userAgent)) return 'iOS device'
  return 'Mobile device'
}

/**
 * Read a small JSON body from a Node request.
 * @param {import('node:http').IncomingMessage} req
 * @param {number} [limit]
 * @returns {Promise<any>}
 */
async function readJsonBody(req, limit = 8192) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > limit) throw new Error('request body too large')
    chunks.push(chunk)
  }
  if (chunks.length === 0) return {}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

export class Proxy {
  /**
   * @param {{store: import('./store.js').Store, hub: import('./tunnel.js').TunnelHub, config: import('./config.js').RelayConfig, logger: ReturnType<import('./log.js').createLogger>, enroll: import('./enroll.js').EnrollmentStore}} options
   */
  constructor({ store, hub, config, logger, enroll }) {
    this.store = store
    this.hub = hub
    this.config = config
    this.logger = logger
    this.enroll = enroll
    this.pairLimiter = new RateLimiter(config.rateLimitPerMinute)
    /** @type {Map<string, number>} */
    this.lastTouch = new Map()
    this.clientWss = new WebSocketServer({
      noServer: true,
      maxPayload: config.maxWsMessageBytes,
      perMessageDeflate: false,
      clientTracking: false,
    })
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async handle(req, res) {
    const url = new URL(req.url ?? '/', 'http://relay.invalid')
    if (url.pathname.startsWith('/api/enroll/')) {
      const host = parseHost(req.headers.host)
      const local = host === '127.0.0.1' || host === 'localhost'
      if (host !== this.config.publicHost && !local) {
        this.writePage(req, res, 404, notFoundPage())
        return
      }
      await this.handleEnroll(req, res, url)
      return
    }
    // Caddy's on-demand TLS ask and health checks use the internal upstream
    // address as Host (`relay:8080`), so these two routes must not depend on
    // the public instance host.
    if (url.pathname === '/internal/tls/ask') {
      this.handleTlsAsk(req, res, url)
      return
    }
    if (url.pathname === '/healthz') {
      this.handleHealth(req, res)
      return
    }
    const host = parseHost(req.headers.host)
    if (host === undefined) {
      this.writePage(req, res, 400, errorPage('请求缺少有效的 Host。'))
      return
    }
    if (host === this.config.publicHost) {
      this.handleControl(req, res)
      return
    }
    const instance = this.resolveInstance(host)
    if (instance === undefined) {
      this.writePage(req, res, 404, notFoundPage())
      return
    }
    try {
      await this.handleInstance(req, res, instance, host)
    } catch (error) {
      this.logger.error('instance request failed', {
        instanceId: instance.id,
        message: error instanceof Error ? error.message : String(error),
      })
      if (!res.headersSent) this.writePage(req, res, 500, errorPage('中继内部错误。', 500))
      else if (!res.writableEnded) res.end()
    }
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {import('./store.js').InstanceRow} instance
   * @param {string} host
   */
  async handleInstance(req, res, instance, host) {
    const url = new URL(req.url ?? '/', 'http://relay.invalid')
    if (url.pathname === '/pair') {
      this.handlePair(req, res, instance, url)
      return
    }
    if (url.pathname === '/favicon.ico') {
      res.writeHead(204, { 'cache-control': 'public, max-age=86400' })
      res.end()
      return
    }
    const device = this.authenticateDevice(req, instance)
    if (device === undefined) {
      this.writePage(req, res, 401, unauthorizedPage())
      return
    }
    if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'].includes(req.method ?? '')) {
      this.writePage(req, res, 405, errorPage('不支持的请求方法。', 405))
      return
    }
    const contentLength = Number(req.headers['content-length'] ?? 0)
    if (Number.isFinite(contentLength) && contentLength > this.config.maxRequestBytes) {
      this.writePage(req, res, 413, errorPage('请求体过大。', 413))
      req.resume()
      return
    }
    const tunnel = this.hub.get(instance.id)
    if (tunnel === undefined) {
      this.writePage(req, res, 503, offlinePage(instance.public_host))
      req.resume()
      return
    }
    this.annotateForwarded(req, host)
    tunnel.openHttp(req, res)
  }

  /**
   * One-time enrollment API used by the plugin page and the relay CLI.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URL} url
   */
  async handleEnroll(req, res, url) {
    const ip = clientIp(req, this.config.trustProxy)
    try {
      if (url.pathname === '/api/enroll/start') {
        if (req.method !== 'POST') {
          this.writeJson(res, 405, { ok: false, error: 'method not allowed' })
          return
        }
        const body = await readJsonBody(req)
        const code = typeof body.code === 'string' ? body.code : ''
        const instanceId = typeof body.instanceId === 'string' ? body.instanceId.trim() : ''
        if (code === '' || instanceId === '') {
          this.writeJson(res, 400, { ok: false, error: 'code and instanceId are required' })
          return
        }
        const result = this.enroll.start({ code, instanceId, ip })
        this.writeJson(res, 200, { ok: true, ...result })
        return
      }
      if (url.pathname === '/api/enroll/claim') {
        if (req.method !== 'POST') {
          this.writeJson(res, 405, { ok: false, error: 'method not allowed' })
          return
        }
        const body = await readJsonBody(req)
        const code = typeof body.code === 'string' ? body.code : ''
        const token = typeof body.token === 'string' ? body.token : ''
        if (code === '' || token === '') {
          this.writeJson(res, 400, { ok: false, error: 'code and token are required' })
          return
        }
        const result = this.enroll.claim({ code, token, ip })
        this.writeJson(res, 200, { ok: true, ...result })
        return
      }
      if (url.pathname.startsWith('/api/enroll/') && req.method === 'GET') {
        const code = decodeURIComponent(url.pathname.slice('/api/enroll/'.length))
        const result = this.enroll.take({ code, ip })
        if (result === undefined) {
          this.writeJson(res, 202, { ok: true, pending: true })
          return
        }
        this.writeJson(res, 200, { ok: true, ...result })
        return
      }
      this.writeJson(res, 404, { ok: false, error: 'not found' })
    } catch (error) {
      const status = error instanceof EnrollmentError ? error.status : 500
      if (status === 500) {
        this.logger.warn('enrollment request failed', {
          message: error instanceof Error ? error.message : String(error),
        })
      }
      this.writeJson(res, status, {
        ok: false,
        error: error instanceof EnrollmentError ? error.message : 'enrollment failed',
      })
    }
  }

  /**
   * @param {import('node:http').ServerResponse} res
   * @param {number} status
   * @param {object} body
   */
  writeJson(res, status, body) {
    const text = JSON.stringify(body)
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(text),
      'cache-control': 'no-store',
      'referrer-policy': 'no-referrer',
      'x-content-type-options': 'nosniff',
    })
    res.end(text)
  }

  /**
   * Caddy's on-demand TLS decision. The request arrives with the internal
   * upstream Host (`relay:8080`), so it is handled before instance routing.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {URL} url
   */
  handleTlsAsk(req, res, url) {
    const domain = url.searchParams.get('domain')?.trim().toLowerCase() ?? ''
    const allowed = domain === this.config.publicHost || this.resolveInstance(domain) !== undefined
    res.writeHead(allowed ? 200 : 404, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
    res.end(allowed ? 'ok\n' : 'unknown host\n')
  }

  /**
   * Health is reachable on the internal management port and through Caddy.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  handleHealth(req, res) {
    const body = {
      ok: true,
      version: '0.2.0',
      instances: this.store.listInstances().length,
      online: this.hub.onlineIds().length,
      time: Date.now(),
    }
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify(body))
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {import('./store.js').InstanceRow} instance
   * @param {URL} url
   */
  handlePair(req, res, instance, url) {
    if (req.method !== 'GET') {
      this.writePage(req, res, 405, errorPage('配对请求必须是 GET。', 405))
      return
    }
    const ip = clientIp(req, this.config.trustProxy)
    if (!this.pairLimiter.take(ip)) {
      this.store.audit('pair.rate_limited', { instanceId: instance.id, ip })
      this.writePage(req, res, 429, pairErrorPage('尝试过于频繁，请稍后再试。'))
      return
    }
    const code = url.searchParams.get('code')?.trim() ?? ''
    if (code === '') {
      this.writePage(req, res, 400, pairErrorPage('这个链接缺少配对码。'))
      return
    }
    const paired = this.store.consumePairCode(code)
    if (paired === undefined || paired.id !== instance.id) {
      this.store.audit('pair.rejected', { instanceId: instance.id, ip })
      this.writePage(req, res, 400, pairErrorPage('配对码无效、已使用或已过期。'))
      return
    }
    const userAgent = typeof req.headers['user-agent'] === 'string' ? req.headers['user-agent'] : undefined
    const requestedName = url.searchParams.get('name') ?? undefined
    const name = requestedName === undefined
      ? deviceNameFromUserAgent(userAgent)
      : sanitizeDeviceName(requestedName)
    const device = this.store.createDevice({ instanceId: instance.id, name, userAgent })
    const value = createDeviceCookie(
      device.id,
      instance.id,
      this.config.deviceCookieDays * 24 * 60 * 60 * 1000,
      this.config.cookieSecret,
    )
    this.store.audit('device.paired', {
      instanceId: instance.id,
      deviceId: device.id,
      ip,
      detail: { name },
    })
    this.hub.notifyDevicePaired(instance.id, device)
    res.writeHead(303, {
      location: '/',
      'set-cookie': serializeDeviceCookie(
        value,
        this.config.deviceCookieDays * 24 * 60 * 60,
        this.config.secureCookies,
      ),
      'cache-control': 'no-store',
      'referrer-policy': 'no-referrer',
    })
    res.end()
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('./store.js').InstanceRow} instance
   * @returns {import('./store.js').DeviceRow|undefined}
   */
  authenticateDevice(req, instance) {
    const cookies = parseCookies(req.headers.cookie)
    const payload = verifyDeviceCookie(cookies[DEVICE_COOKIE], this.config.cookieSecret)
    if (payload === undefined || payload.i !== instance.id) return undefined
    const device = this.store.authenticateDevice(payload.d, instance.id)
    if (device === undefined) return undefined
    const now = Date.now()
    if (now - (this.lastTouch.get(device.id) ?? 0) > 60_000) {
      this.lastTouch.set(device.id, now)
      this.store.touchDevice(device.id, now)
    }
    return device
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {string} host
   */
  annotateForwarded(req, host) {
    const ip = clientIp(req, this.config.trustProxy)
    req.headers['x-forwarded-for'] = ip
    req.headers['x-forwarded-proto'] = this.config.publicUrl.startsWith('https://') ? 'https' : 'http'
    req.headers['x-forwarded-host'] = host
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  handleControl(req, res) {
    const url = new URL(req.url ?? '/', 'http://relay.invalid')
    if (url.pathname === '/healthz') {
      this.handleHealth(req, res)
      return
    }
    if (url.pathname === '/internal/tls/ask') {
      this.handleTlsAsk(req, res, url)
      return
    }
    if (url.pathname === '/api/version') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      res.end(JSON.stringify({ name: 'dsh-remote-tunnel-relay', version: '0.2.0', protocol: 1 }))
      return
    }
    if (url.pathname === '/robots.txt') {
      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
      res.end('User-agent: *\nDisallow: /\n')
      return
    }
    if (url.pathname === '/') {
      this.writePage(req, res, 200, landingPage({
        publicHost: this.config.publicHost,
        instanceBaseDomain: this.config.instanceBaseDomain,
      }))
      return
    }
    this.writePage(req, res, 404, notFoundPage())
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:stream').Duplex} socket
   * @param {Buffer} head
   */
  handleUpgrade(req, socket, head) {
    const host = parseHost(req.headers.host)
    if (host === undefined || host === this.config.publicHost) {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const instance = this.resolveInstance(host)
    if (instance === undefined) {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    if (this.authenticateDevice(req, instance) === undefined) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    const tunnel = this.hub.get(instance.id)
    if (tunnel === undefined) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n')
      socket.destroy()
      return
    }
    this.annotateForwarded(req, host)
    this.clientWss.handleUpgrade(req, socket, head, (client) => {
      tunnel.openWs(req, client)
    })
  }

  /**
   * @param {string} host
   * @returns {import('./store.js').InstanceRow|undefined}
   */
  resolveInstance(host) {
    const direct = this.store.getInstanceByHost(host)
    if (direct !== undefined && direct.revoked_at === null) return direct
    const suffix = `.${this.config.instanceBaseDomain}`
    if (!host.endsWith(suffix)) return undefined
    const id = host.slice(0, -suffix.length)
    if (!INSTANCE_ID.test(id)) return undefined
    const instance = this.store.getInstance(id)
    if (instance === undefined || instance.revoked_at !== null) return undefined
    if (instance.public_host !== host) return undefined
    return instance
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {number} status
   * @param {string} html
   */
  writePage(req, res, status, html) {
    res.writeHead(status, {
      'content-type': 'text/html; charset=utf-8',
      'content-length': Buffer.byteLength(html),
      'cache-control': 'no-store',
      'referrer-policy': 'no-referrer',
      'x-content-type-options': 'nosniff',
      'x-frame-options': 'DENY',
      'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    })
    if (req.method === 'HEAD') res.end()
    else res.end(html)
  }
}
