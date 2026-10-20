/**
 * Local DSH proxy: mint a DSH browser cookie for the public host, inject it,
 * and forward HTTP/WebSocket streams from the relay.
 */

import http from 'node:http'
import { WebSocket } from 'ws'

const COOKIE_HEADER = 'set-cookie'

/** Headers that never go to the local DSH server. */
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
 * @param {unknown} error
 * @returns {string}
 */
function messageOf(error) {
  return error instanceof Error ? error.message : String(error)
}

/**
 * Extract `dsh-auth-*` cookies from a Set-Cookie header.
 * @param {string|string[]|undefined} header
 * @returns {string|undefined}
 */
export function extractDshCookie(header) {
  if (header === undefined) return undefined
  const values = Array.isArray(header) ? header : [header]
  const cookies = []
  for (const value of values) {
    const pair = String(value).split(';')[0].trim()
    if (pair.startsWith('dsh-auth-')) cookies.push(pair)
  }
  return cookies.length === 0 ? undefined : cookies.join('; ')
}

export class DshProxy {
  /**
   * @param {{localHost: string, localPort: number, publicHost: string, getTokenUrl: () => string|undefined, logger: any, maxWsMessageBytes?: number}} options
   */
  constructor({ localHost, localPort, publicHost, getTokenUrl, logger, maxWsMessageBytes = 8 * 1024 * 1024 }) {
    this.localHost = localHost
    this.localPort = localPort
    this.publicHost = publicHost
    this.getTokenUrl = getTokenUrl
    this.logger = logger
    this.maxWsMessageBytes = maxWsMessageBytes
    this.cookie = undefined
    this.mintedAt = 0
    this.agent = new http.Agent({ keepAlive: true, maxSockets: 64 })
    this.refreshing = undefined
  }

  /**
   * @param {boolean} [force]
   * @returns {Promise<string>}
   */
  async ensureCookie(force = false) {
    const maxAge = 6 * 60 * 60 * 1000
    if (!force && this.cookie !== undefined && Date.now() - this.mintedAt < maxAge) return this.cookie
    if (this.refreshing !== undefined) return this.refreshing
    this.refreshing = this.mintCookie().finally(() => { this.refreshing = undefined })
    return this.refreshing
  }

  /**
   * @returns {Promise<string>}
   */
  async mintCookie() {
    const tokenUrl = this.getTokenUrl()
    if (tokenUrl === undefined || tokenUrl === '') {
      throw new Error('DSH connection.authenticatedUrl() is unavailable; cannot mint a DSH session cookie')
    }
    const url = new URL(tokenUrl)
    const path = `${url.pathname}${url.search}`
    const cookie = await new Promise((resolve, reject) => {
      const req = http.request({
        host: this.localHost,
        port: this.localPort,
        method: 'GET',
        path,
        agent: false,
        setHost: false,
        timeout: 10_000,
        headers: {
          host: this.publicHost,
          connection: 'close',
          accept: 'text/html',
        },
      }, (res) => {
        const header = res.headers[COOKIE_HEADER]
        res.resume()
        const value = extractDshCookie(header)
        if (value === undefined) {
          reject(new Error(`DSH did not set a session cookie (HTTP ${String(res.statusCode ?? 0)})`))
          return
        }
        resolve(value)
      })
      req.on('timeout', () => req.destroy(new Error('DSH cookie request timed out')))
      req.on('error', reject)
      req.end()
    })
    this.cookie = cookie
    this.mintedAt = Date.now()
    this.logger.info('dsh-remote-tunnel: minted a DSH browser cookie for the public host')
    return cookie
  }

  /**
   * @param {Record<string, any>} incoming
   * @returns {Record<string, any>}
   */
  forwardHeaders(incoming) {
    const headers = {}
    for (const [name, value] of Object.entries(incoming)) {
      if (value === undefined) continue
      const lower = name.toLowerCase()
      if (HOP_BY_HOP.has(lower)) continue
      if (lower === 'host' || lower === 'cookie' || lower === 'authorization') continue
      if (lower.startsWith('x-dsh-')) continue
      headers[lower] = value
    }
    headers.host = this.publicHost
    headers.cookie = this.cookie
    return headers
  }

  /**
   * Start an HTTP request to the local DSH server. The caller writes the body
   * and reads the response.
   * @param {{method: string, path: string, headers: Record<string, any>, onResponse: (res: http.IncomingMessage) => void, onError: (error: Error) => void}} options
   * @returns {Promise<http.ClientRequest>}
   */
  async request({ method, path, headers, onResponse, onError }) {
    const cookie = await this.ensureCookie()
    const requestHeaders = { ...this.forwardHeaders(headers), cookie }
    const req = http.request({
      host: this.localHost,
      port: this.localPort,
      method,
      path,
      headers: requestHeaders,
      agent: this.agent,
      setHost: false,
    })
    req.on('response', (res) => {
      if (res.statusCode === 401 || res.statusCode === 403) {
        // The cookie can outlive a DSH restart only until the secret rotates.
        // Refresh once for idempotent requests; body-bearing requests cannot
        // be replayed safely here.
        if (method === 'GET' || method === 'HEAD') {
          res.resume()
          this.cookie = undefined
          this.ensureCookie(true).catch(error => {
            this.logger.warn(`dsh-remote-tunnel: cookie refresh failed: ${messageOf(error)}`)
          })
        }
      }
      onResponse(res)
    })
    req.on('error', (error) => onError(error))
    return req
  }

  /**
   * Open a WebSocket to the local DSH server with the injected cookie.
   * @param {{path: string, headers: Record<string, any>}} options
   * @returns {Promise<WebSocket>}
   */
  async webSocket({ path, headers }) {
    const cookie = await this.ensureCookie()
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(`ws://${this.localHost}:${this.localPort}${path}`, {
        headers: {
          ...this.forwardHeaders(headers),
          cookie,
        },
        perMessageDeflate: false,
        maxPayload: this.maxWsMessageBytes + 1024,
        handshakeTimeout: 10_000,
        followRedirects: false,
      })
      const onError = (error) => {
        socket.off('open', onOpen)
        reject(error)
      }
      const onOpen = () => {
        socket.off('error', onError)
        resolve(socket)
      }
      socket.on('unexpected-response', (request, response) => {
        response.resume()
        request.destroy()
        reject(new Error(`DSH WebSocket upgrade failed (HTTP ${String(response.statusCode ?? 0)})`))
      })
      socket.once('error', onError)
      socket.once('open', onOpen)
    })
  }

  status() {
    return {
      cookieMinted: this.cookie !== undefined,
      cookieAgeMs: this.cookie === undefined ? 0 : Date.now() - this.mintedAt,
    }
  }

  close() {
    this.agent.destroy()
  }
}
