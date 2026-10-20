/** Signed, host-only device cookie. */

import { createHmac, timingSafeEqual } from 'node:crypto'

export const DEVICE_COOKIE = '__dsh_device'
const VERSION = 1

/**
 * @typedef {object} DeviceCookiePayload
 * @property {1} v
 * @property {string} d - device id
 * @property {string} i - instance id
 * @property {number} exp - absolute expiry in ms
 */

function base64url(buffer) {
  return Buffer.from(buffer).toString('base64')
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '')
}

function fromBase64url(value) {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) return undefined
  const padding = '='.repeat((4 - value.length % 4) % 4)
  return Buffer.from(value.replaceAll('-', '+').replaceAll('_', '/') + padding, 'base64')
}

function signature(secret, body) {
  return createHmac('sha256', secret).update(body).digest()
}

/**
 * Create a device cookie value.
 * @param {string} deviceId
 * @param {string} instanceId
 * @param {number} ttlMs
 * @param {string} secret
 * @param {number} [now]
 * @returns {string}
 */
export function createDeviceCookie(deviceId, instanceId, ttlMs, secret, now = Date.now()) {
  const payload = { v: VERSION, d: deviceId, i: instanceId, exp: now + ttlMs }
  const body = base64url(Buffer.from(JSON.stringify(payload), 'utf8'))
  return `${body}.${base64url(signature(secret, body))}`
}

/**
 * Verify a device cookie value.
 * @param {string} value
 * @param {string} secret
 * @param {number} [now]
 * @returns {DeviceCookiePayload|undefined}
 */
export function verifyDeviceCookie(value, secret, now = Date.now()) {
  if (typeof value !== 'string') return undefined
  const parts = value.split('.')
  if (parts.length !== 2) return undefined
  const [body, encoded] = parts
  const expected = base64url(signature(secret, body))
  if (encoded.length !== expected.length) return undefined
  if (!timingSafeEqual(Buffer.from(encoded), Buffer.from(expected))) return undefined
  const decoded = fromBase64url(body)
  if (decoded === undefined) return undefined
  let payload
  try {
    payload = JSON.parse(decoded.toString('utf8'))
  } catch {
    return undefined
  }
  if (payload === null || typeof payload !== 'object') return undefined
  if (payload.v !== VERSION || typeof payload.d !== 'string' || typeof payload.i !== 'string'
    || !Number.isFinite(payload.exp) || payload.exp <= now) return undefined
  return /** @type {DeviceCookiePayload} */ (payload)
}

/**
 * Parse a Cookie header into a map of the last occurrence of each name.
 * @param {string|undefined} header
 * @returns {Record<string, string>}
 */
export function parseCookies(header) {
  /** @type {Record<string, string>} */
  const cookies = {}
  if (header === undefined) return cookies
  for (const segment of header.split(';')) {
    const at = segment.indexOf('=')
    if (at === -1) continue
    const name = segment.slice(0, at).trim()
    if (name === '') continue
    cookies[name] = segment.slice(at + 1).trim()
  }
  return cookies
}

/**
 * Serialize the device cookie. No `Domain` attribute: it is host-only.
 * @param {string} value
 * @param {number} maxAgeSeconds
 * @param {boolean} secure
 * @returns {string}
 */
export function serializeDeviceCookie(value, maxAgeSeconds, secure) {
  const attributes = [
    `${DEVICE_COOKIE}=${value}`,
    `Max-Age=${String(maxAgeSeconds)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
  ]
  if (secure) attributes.push('Secure')
  return attributes.join('; ')
}

/**
 * Remove the relay device cookie from a Cookie header before forwarding it.
 * @param {string|undefined} header
 * @returns {string|undefined}
 */
export function stripDeviceCookie(header) {
  if (header === undefined) return undefined
  const kept = header.split(';').filter(segment => {
    const at = segment.indexOf('=')
    if (at === -1) return false
    return segment.slice(0, at).trim() !== DEVICE_COOKIE
  }).map(segment => segment.trim()).filter(Boolean)
  return kept.length === 0 ? undefined : kept.join('; ')
}
