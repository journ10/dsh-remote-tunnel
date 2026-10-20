/** Relay configuration from environment variables. */

import path from 'node:path'

/**
 * @typedef {object} RelayConfig
 * @property {string} publicHost
 * @property {string} instanceBaseDomain
 * @property {string} publicUrl
 * @property {number} port
 * @property {string} dataDir
 * @property {boolean} secureCookies
 * @property {string} cookieSecret
 * @property {number} pairTtlMs
 * @property {number} pairCodeLength
 * @property {number} deviceCookieDays
 * @property {number} maxRequestBytes
 * @property {number} maxWsMessageBytes
 * @property {number} rateLimitPerMinute
 * @property {{id: string, token: string, publicHost?: string}|undefined} bootstrap
 * @property {boolean} trustProxy
 * @property {string} logLevel
 */

function required(env, name) {
  const value = env[name]?.trim()
  if (value === undefined || value === '') {
    throw new Error(`${name} is required`)
  }
  return value
}

function positiveInt(env, name, fallback, { min = 1, max = Number.MAX_SAFE_INTEGER } = {}) {
  const raw = env[name]
  if (raw === undefined || raw.trim() === '') return fallback
  const value = Number(raw)
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer in ${min}..${max}`)
  }
  return value
}

function boolean(env, name, fallback) {
  const raw = env[name]?.trim().toLowerCase()
  if (raw === undefined || raw === '') return fallback
  if (raw === '1' || raw === 'true' || raw === 'yes') return true
  if (raw === '0' || raw === 'false' || raw === 'no') return false
  throw new Error(`${name} must be true or false`)
}

/**
 * Validate a bare DNS host (no scheme, port, path, or wildcard).
 * @param {string} value
 * @param {string} name
 * @returns {string}
 */
export function normalizeHost(value, name = 'host') {
  const host = value.trim().toLowerCase().replace(/\.$/u, '')
  if (!/^[a-z0-9](?:[a-z0-9-]{0,62})(?:\.[a-z0-9](?:[a-z0-9-]{0,62}))*$/u.test(host)) {
    throw new Error(`${name} must be a bare DNS name, got ${JSON.stringify(value)}`)
  }
  return host
}

/**
 * Load and validate the relay configuration.
 * @param {NodeJS.ProcessEnv} [env]
 * @returns {RelayConfig}
 */
export function loadConfig(env = process.env) {
  const publicHost = normalizeHost(required(env, 'PUBLIC_HOST'), 'PUBLIC_HOST')
  const instanceBaseDomain = normalizeHost(
    env.INSTANCE_BASE_DOMAIN?.trim() || publicHost,
    'INSTANCE_BASE_DOMAIN',
  )
  const publicUrl = (env.PUBLIC_URL?.trim() || `https://${publicHost}`).replace(/\/+$/u, '')
  if (!/^https?:\/\//u.test(publicUrl)) {
    throw new Error('PUBLIC_URL must start with http:// or https://')
  }

  const secureCookies = boolean(env, 'COOKIE_SECURE', publicUrl.startsWith('https://'))
  const cookieSecret = required(env, 'COOKIE_SECRET')
  if (cookieSecret.length < 32) {
    throw new Error('COOKIE_SECRET must be at least 32 characters')
  }

  const bootstrapId = env.BOOTSTRAP_INSTANCE_ID?.trim()
  const bootstrapToken = env.BOOTSTRAP_INSTANCE_TOKEN?.trim()
  let bootstrap
  if (bootstrapId !== undefined && bootstrapId !== '') {
    if (bootstrapToken === undefined || bootstrapToken.length < 16) {
      throw new Error('BOOTSTRAP_INSTANCE_TOKEN must be at least 16 characters when BOOTSTRAP_INSTANCE_ID is set')
    }
    const publicHostOverride = env.BOOTSTRAP_PUBLIC_HOST?.trim()
    bootstrap = {
      id: bootstrapId,
      token: bootstrapToken,
      publicHost: publicHostOverride === undefined || publicHostOverride === ''
        ? `${bootstrapId}.${instanceBaseDomain}`
        : normalizeHost(publicHostOverride, 'BOOTSTRAP_PUBLIC_HOST'),
    }
  }

  return {
    publicHost,
    instanceBaseDomain,
    publicUrl,
    port: positiveInt(env, 'PORT', 8080, { min: 1, max: 65535 }),
    dataDir: path.resolve(env.DATA_DIR?.trim() || './data'),
    secureCookies,
    cookieSecret,
    pairTtlMs: positiveInt(env, 'PAIR_TTL_MS', 120_000, { min: 15_000, max: 900_000 }),
    pairCodeLength: positiveInt(env, 'PAIR_CODE_LENGTH', 10, { min: 8, max: 16 }),
    deviceCookieDays: positiveInt(env, 'DEVICE_COOKIE_DAYS', 30, { min: 1, max: 365 }),
    maxRequestBytes: positiveInt(env, 'MAX_REQUEST_BYTES', 512 * 1024 * 1024),
    maxWsMessageBytes: positiveInt(env, 'MAX_WS_MESSAGE_BYTES', 1024 * 1024),
    rateLimitPerMinute: positiveInt(env, 'RATE_LIMIT_PER_MINUTE', 30, { min: 1 }),
    bootstrap,
    trustProxy: boolean(env, 'TRUST_PROXY', true),
    logLevel: (env.LOG_LEVEL?.trim() || 'info').toLowerCase(),
  }
}
