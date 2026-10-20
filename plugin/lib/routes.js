/** DSH webserver routes used by the Plugins page. */

import { randomInt } from 'node:crypto'
import QRCode from 'qrcode'
import {
  normalizeInstanceId,
  normalizeRelayUrl,
  writeSettings,
  writeTokenFile,
} from './config.js'

const MAX_BODY_BYTES = 16 * 1024
const ENROLL_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
const ENROLL_CODE_LENGTH = 10
const ENROLL_PATTERN = /^[A-Z2-9]{8,16}$/u

/**
 * @returns {string}
 */
function makeEnrollmentCode() {
  let code = ''
  for (let i = 0; i < ENROLL_CODE_LENGTH; i += 1) code += ENROLL_ALPHABET[randomInt(ENROLL_ALPHABET.length)]
  return code
}

/**
 * Turn a wss/ws relay URL into the public https/http origin.
 * @param {string} relayUrl
 * @returns {string}
 */
function relayHttpBase(relayUrl) {
  const url = new URL(relayUrl)
  url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:'
  url.pathname = ''
  url.search = ''
  url.hash = ''
  return url.origin
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {number} limit
 * @returns {Promise<any>}
 */
async function readJson(req, limit) {
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

/**
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {object} body
 */
function json(res, status, body) {
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
 * @param {import('node:http').IncomingMessage} req
 * @param {import('./config.js').ResolvedConfig} settings
 * @returns {boolean}
 */
function originAllowed(req, settings) {
  const origin = req.headers.origin
  if (origin === undefined) return true
  const allowed = new Set([
    `https://${settings.domain}`,
    `http://${settings.domain}`,
    `http://127.0.0.1:${String(settings.localPort)}`,
    `http://localhost:${String(settings.localPort)}`,
    `http://${settings.localHost}:${String(settings.localPort)}`,
    `https://${settings.localHost}:${String(settings.localPort)}`,
  ])
  for (const host of settings.extraTrustedHosts) {
    allowed.add(`http://${host}`)
    allowed.add(`https://${host}`)
  }
  return allowed.has(origin)
}

/**
 * Register the plugin's local routes.
 * @param {any} webCtx - context with `webServer`.
 * @param {{runtime: import('./runtime.js').TunnelRuntime, logger: any, getConfig: () => import('./config.js').ResolvedConfig}} options
 * @returns {() => void}
 */
export function registerRoutes(webCtx, { runtime, logger, getConfig }) {
  const disposers = []
  const prefix = '/remote-tunnel'
  const settings = () => runtime.settings

  const status = (req, res) => {
    if (req.method !== 'GET') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    json(res, 200, { ok: true, ...runtime.status() })
  }

  const config = async (req, res) => {
    if (req.method !== 'POST') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    if (!originAllowed(req, settings())) {
      json(res, 403, { ok: false, error: '请求来源不允许' })
      return
    }
    try {
      const body = await readJson(req, MAX_BODY_BYTES)
      const relayInput = typeof body.relayUrl === 'string' ? body.relayUrl.trim() : ''
      if (relayInput === '' && settings().relayUrl === '') {
        json(res, 400, { ok: false, error: '请填写 Relay URL' })
        return
      }
      const relayUrl = relayInput === '' ? settings().relayUrl : normalizeRelayUrl(relayInput)
      const instanceInput = typeof body.instanceId === 'string' ? body.instanceId.trim() : ''
      const instanceId = instanceInput === ''
        ? settings().instanceId
        : normalizeInstanceId(instanceInput)
      const tokenInput = typeof body.instanceToken === 'string' ? body.instanceToken.trim() : ''
      if (tokenInput !== '' && tokenInput.length < 16) {
        json(res, 400, { ok: false, error: '实例令牌长度不正确' })
        return
      }
      if (tokenInput === '' && settings().instanceToken === undefined) {
        json(res, 400, { ok: false, error: '请填写实例令牌' })
        return
      }
      if (tokenInput !== '') writeTokenFile(settings().tokenFile, tokenInput)
      writeSettings({ relayUrl, instanceId, tokenFile: settings().tokenFile })
      const next = getConfig()
      const result = await runtime.reconfigure(next)
      logger.info('dsh-remote-tunnel: relay settings saved', {
        relayUrl: next.relayUrl,
        instanceId: next.instanceId,
        restartRequired: result.restartRequired === true,
      })
      json(res, 200, { ok: true, ...result, status: runtime.status() })
    } catch (error) {
      logger.warn(`dsh-remote-tunnel: saving relay settings failed: ${error instanceof Error ? error.message : String(error)}`)
      json(res, 400, { ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  const enrollStart = async (req, res) => {
    if (req.method !== 'POST') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    if (!originAllowed(req, settings())) {
      json(res, 403, { ok: false, error: '请求来源不允许' })
      return
    }
    if (settings().relayUrl === '') {
      json(res, 400, { ok: false, error: '请先填写 Relay URL' })
      return
    }
    try {
      await readJson(req, MAX_BODY_BYTES)
      const code = makeEnrollmentCode()
      const base = relayHttpBase(settings().relayUrl)
      const response = await fetch(`${base}/api/enroll/start`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code, instanceId: settings().instanceId }),
        signal: AbortSignal.timeout(10_000),
      })
      const body = await response.json().catch(() => ({}))
      if (!response.ok || body.ok === false) {
        throw new Error(body.error ?? `relay returned HTTP ${String(response.status)}`)
      }
      const command = `sudo docker compose -f /opt/dsh-remote-tunnel/docker-compose.yml exec -T relay node src/cli.js enroll ${code}`
      logger.info('dsh-remote-tunnel: enrollment code created')
      json(res, 200, {
        ok: true,
        code,
        expiresAt: body.expiresAt,
        command,
        relayUrl: settings().relayUrl,
      })
    } catch (error) {
      logger.warn(`dsh-remote-tunnel: enrollment start failed: ${error instanceof Error ? error.message : String(error)}`)
      json(res, 502, { ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  const enrollPoll = async (req, res) => {
    if (req.method !== 'GET') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    if (!originAllowed(req, settings())) {
      json(res, 403, { ok: false, error: '请求来源不允许' })
      return
    }
    if (settings().relayUrl === '') {
      json(res, 400, { ok: false, error: '请先填写 Relay URL' })
      return
    }
    const code = new URL(req.url ?? '/', 'http://relay.invalid').searchParams.get('code')?.trim() ?? ''
    if (!ENROLL_PATTERN.test(code)) {
      json(res, 400, { ok: false, error: '导入码无效' })
      return
    }
    try {
      const base = relayHttpBase(settings().relayUrl)
      const response = await fetch(`${base}/api/enroll/${encodeURIComponent(code)}`, {
        signal: AbortSignal.timeout(10_000),
      })
      if (response.status === 202) {
        json(res, 200, { ok: true, pending: true })
        return
      }
      const body = await response.json().catch(() => ({}))
      if (!response.ok || body.ok === false) {
        json(res, 404, { ok: false, error: body.error ?? '导入码已过期，请重新生成' })
        return
      }
      if (typeof body.token !== 'string' || body.token === '') {
        json(res, 502, { ok: false, error: 'relay 没有返回实例令牌' })
        return
      }
      writeTokenFile(settings().tokenFile, body.token)
      writeSettings({ relayUrl: settings().relayUrl, instanceId: settings().instanceId, tokenFile: settings().tokenFile })
      const next = getConfig()
      const result = await runtime.reconfigure(next)
      logger.info('dsh-remote-tunnel: instance token imported from the relay')
      json(res, 200, { ok: true, imported: true, ...result, status: runtime.status() })
    } catch (error) {
      logger.warn(`dsh-remote-tunnel: enrollment poll failed: ${error instanceof Error ? error.message : String(error)}`)
      json(res, 502, { ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  const pair = async (req, res) => {
    if (req.method !== 'POST') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    if (!originAllowed(req, settings())) {
      json(res, 403, { ok: false, error: '请求来源不允许' })
      return
    }
    if (!settings().pairingEnabled) {
      json(res, 403, { ok: false, error: 'pairing is disabled' })
      return
    }
    try {
      await readJson(req, MAX_BODY_BYTES)
      const code = await runtime.createPairCode(120_000)
      const svg = await QRCode.toString(code.url, {
        type: 'svg',
        margin: 1,
        width: 240,
        errorCorrectionLevel: 'M',
      })
      logger.info('dsh-remote-tunnel: pairing code created')
      json(res, 200, { ok: true, ...code, svg })
    } catch (error) {
      logger.warn(`dsh-remote-tunnel: pairing code failed: ${error instanceof Error ? error.message : String(error)}`)
      json(res, 503, { ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  const revoke = async (req, res) => {
    if (req.method !== 'POST') {
      json(res, 405, { ok: false, error: '不支持的请求方法' })
      return
    }
    if (!originAllowed(req, settings())) {
      json(res, 403, { ok: false, error: '请求来源不允许' })
      return
    }
    try {
      const body = await readJson(req, MAX_BODY_BYTES)
      const deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim() : ''
      if (deviceId === '') {
        json(res, 400, { ok: false, error: 'deviceId is required' })
        return
      }
      const devices = await runtime.revokeDevice(deviceId)
      logger.info('dsh-remote-tunnel: device revoked', { deviceId })
      json(res, 200, { ok: true, devices })
    } catch (error) {
      logger.warn(`dsh-remote-tunnel: revoke failed: ${error instanceof Error ? error.message : String(error)}`)
      json(res, 503, { ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  for (const [path, handler] of [
    [`${prefix}/status`, status],
    [`${prefix}/config`, config],
    [`${prefix}/enroll/start`, enrollStart],
    [`${prefix}/enroll/poll`, enrollPoll],
    [`${prefix}/pair`, pair],
    [`${prefix}/revoke`, revoke],
  ]) {
    disposers.push(webCtx.webServer.register({ kind: 'exact', path, handler }))
  }
  return () => { for (const dispose of disposers) dispose() }
}
