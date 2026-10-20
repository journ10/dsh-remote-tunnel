/** Plugin configuration, token storage, and relay URL normalization. */

import { chmodSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import z from '@deepseek-ai/schemastery'

const DOMAIN_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,62})(?:\.[a-z0-9](?:[a-z0-9-]{0,62}))+$/iu
const INSTANCE_ID = /^[a-z0-9][a-z0-9-]{0,31}$/u

/** Row configuration. Secrets should live in `tokenFile`, not here. */
export const Config = z.object({
  /** `relay` uses the HTTPS tunnel; `ssh` keeps the legacy reverse tunnel. */
  mode: z.string().default('relay'),
  /** Public instance host, e.g. `mac.relay.example.com`. */
  domain: z.string().required().volatile(),
  /** Relay WebSocket URL, e.g. `wss://relay.example.com/tunnel`. */
  relayUrl: z.string().default(''),
  /** Instance id registered on the relay. */
  instanceId: z.string().default('default'),
  /** Instance token; prefer `tokenFile`. */
  instanceToken: z.string().default(''),
  /** Token file path; defaults to `$DSH_HOME/dsh-remote-tunnel/instance-token`. */
  tokenFile: z.string().default(''),
  /** Local DSH HTTP host. */
  localHost: z.string().default('127.0.0.1'),
  /** Local DSH HTTP port. */
  localPort: z.number().min(1).max(65535).step(1).default(3080),
  /** Extra authorities accepted by DSH's Host/Origin fence. */
  extraTrustedHosts: z.array(z.string()).default([]),
  /** Show the pairing QR code in the Plugins page. */
  pairingEnabled: z.boolean().default(true),
  /** Legacy SSH: server-side bind address. */
  remoteBind: z.string().default('127.0.0.1:3080'),
  /** Legacy SSH: destination host. */
  sshHost: z.string().default(''),
  /** Legacy SSH: user. */
  sshUser: z.string().default(''),
  /** Legacy SSH: port. */
  sshPort: z.number().min(1).max(65535).step(1).default(22),
  /** Legacy SSH: private key. */
  identityFile: z.string().default(''),
})

/**
 * @returns {string} the default token file path.
 */
export function defaultTokenFile() {
  const home = process.env.DSH_HOME?.trim() || path.join(os.homedir(), '.dsh')
  return path.join(home, 'dsh-remote-tunnel', 'instance-token')
}

/**
 * @returns {string} the plugin state directory.
 */
export function stateDirectory() {
  const home = process.env.DSH_HOME?.trim() || path.join(os.homedir(), '.dsh')
  return path.join(home, 'dsh-remote-tunnel')
}

/**
 * Runtime settings written by the Plugins page. The token itself lives in
 * `tokenFile`, never in this JSON.
 * @returns {string}
 */
export function settingsFile() {
  return path.join(stateDirectory(), 'settings.json')
}

/**
 * @param {{warn: (message: string) => void}} [logger]
 * @returns {{relayUrl?: string, instanceId?: string, updatedAt?: number}}
 */
export function readSettings(logger) {
  const file = settingsFile()
  try {
    const text = readFileSync(file, 'utf8')
    const parsed = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      logger?.warn(`ignoring invalid settings file ${file}: expected a JSON object`)
      return {}
    }
    return parsed
  } catch (error) {
    if (error !== null && typeof error === 'object' && error.code === 'ENOENT') return {}
    logger?.warn(`ignoring unreadable settings file ${file}: ${error instanceof Error ? error.message : String(error)}`)
    return {}
  }
}

/**
 * Persist non-secret runtime settings with owner-only permissions.
 * @param {{relayUrl?: string, instanceId?: string}} update
 */
export function writeSettings(update) {
  const current = readSettings()
  const next = { ...current, ...update, updatedAt: Date.now() }
  mkdirSync(stateDirectory(), { recursive: true, mode: 0o700 })
  writeFileSync(settingsFile(), `${JSON.stringify(next, null, 2)}\n`, { mode: 0o600 })
  chmodSync(settingsFile(), 0o600)
  return next
}

/**
 * Normalize a relay URL to a WebSocket endpoint ending in `/tunnel`.
 * @param {string} raw
 * @returns {string}
 */
export function normalizeRelayUrl(raw) {
  const value = raw.trim()
  if (value === '') throw new TypeError('relayUrl is required in relay mode')
  let url
  try {
    url = new URL(value.replace(/^http:/iu, 'ws:').replace(/^https:/iu, 'wss:'))
  } catch {
    throw new TypeError('relayUrl must be a valid http(s) or ws(s) URL')
  }
  if (url.protocol !== 'wss:' && url.protocol !== 'ws:') {
    throw new TypeError('relayUrl must use https:// or wss://')
  }
  if (url.username !== '' || url.password !== '') {
    throw new TypeError('relayUrl must not carry credentials')
  }
  if (url.pathname === '' || url.pathname === '/') url.pathname = '/tunnel'
  url.search = ''
  url.hash = ''
  return url.href.replace(/\/$/u, '')
}

/**
 * @param {string} value
 * @returns {string}
 */
export function normalizeDomain(value) {
  const domain = value.trim().toLowerCase()
  if (!DOMAIN_PATTERN.test(domain)) {
    throw new TypeError('domain must be a bare public hostname, e.g. mac.relay.example.com')
  }
  return domain
}

/**
 * @param {string} value
 * @returns {string}
 */
export function normalizeInstanceId(value) {
  const id = value.trim().toLowerCase()
  if (!INSTANCE_ID.test(id)) {
    throw new TypeError('instanceId must be 1-32 characters of a-z, 0-9, or hyphen')
  }
  return id
}

/**
 * Read the token file and warn when it is readable by other users.
 * @param {string} file
 * @param {{warn: (message: string) => void}} [logger]
 * @returns {string|undefined}
 */
export function readTokenFile(file, logger) {
  try {
    const stat = statSync(file)
    if ((stat.mode & 0o077) !== 0) {
      logger?.warn(`token file ${file} is readable by group/others; run: chmod 600 ${file}`)
    }
    const value = readFileSync(file, 'utf8').trim()
    return value === '' ? undefined : value
  } catch (error) {
    if (error !== null && typeof error === 'object' && error.code === 'ENOENT') return undefined
    throw error
  }
}

/**
 * Write a token file with owner-only permissions.
 * @param {string} file
 * @param {string} token
 */
export function writeTokenFile(file, token) {
  mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 })
  writeFileSync(file, `${token.trim()}\n`, { mode: 0o600 })
  chmodSync(file, 0o600)
}

/**
 * @typedef {object} ResolvedConfig
 * @property {'relay'|'ssh'} mode
 * @property {string} domain
 * @property {string} publicUrl
 * @property {string} relayUrl
 * @property {string} instanceId
 * @property {string|undefined} instanceToken
 * @property {string} tokenFile
 * @property {string} settingsFile
 * @property {string} localHost
 * @property {number} localPort
 * @property {string[]} extraTrustedHosts
 * @property {boolean} pairingEnabled
 * @property {string} remoteBind
 * @property {string} sshHost
 * @property {string|undefined} sshUser
 * @property {number} sshPort
 * @property {string|undefined} identityFile
 */

/**
 * Validate and normalize the row config plus token source.
 * @param {any} config
 * @param {{warn: (message: string) => void, info?: (message: string) => void}} [logger]
 * @returns {ResolvedConfig}
 */
export function resolveConfig(config, logger) {
  const value = (key) => {
    const raw = config?.[key]
    return raw !== null && typeof raw === 'object' && typeof raw.get === 'function' ? raw.get() : raw
  }
  const domain = normalizeDomain(value('domain') ?? '')
  if (domain === '') throw new TypeError('domain is required')
  const mode = String(value('mode') ?? 'relay').trim().toLowerCase()
  if (mode !== 'relay' && mode !== 'ssh') throw new TypeError('mode must be relay or ssh')

  const localPort = Number(value('localPort') ?? 3080)
  if (!Number.isInteger(localPort) || localPort < 1 || localPort > 65535) {
    throw new TypeError('localPort must be an integer in 1..65535')
  }
  const localHost = String(value('localHost') ?? '127.0.0.1').trim()
  if (localHost === '') throw new TypeError('localHost must not be empty')

  const runtimeSettings = readSettings(logger)
  const instanceId = normalizeInstanceId(
    String(runtimeSettings.instanceId ?? '').trim() || String(value('instanceId') ?? '').trim() || 'default',
  )
  const tokenFile = String(value('tokenFile') ?? '').trim()
    || String(runtimeSettings.tokenFile ?? '').trim()
    || defaultTokenFile()
  const explicitToken = String(value('instanceToken') ?? '').trim()
  const fileToken = explicitToken === '' ? readTokenFile(tokenFile, logger) : undefined
  const instanceToken = explicitToken === '' ? fileToken : explicitToken
  const rawRelayUrl = String(runtimeSettings.relayUrl ?? '').trim() || String(value('relayUrl') ?? '').trim()
  const relayUrl = mode === 'relay' && rawRelayUrl !== '' ? normalizeRelayUrl(rawRelayUrl) : ''

  const remoteBind = String(value('remoteBind') ?? '127.0.0.1:3080').trim()
  if (!/^.+:\d{1,5}$/u.test(remoteBind)) throw new TypeError('remoteBind must be host:port')
  const remotePort = Number(remoteBind.slice(remoteBind.lastIndexOf(':') + 1))
  if (!Number.isInteger(remotePort) || remotePort < 1 || remotePort > 65535) {
    throw new TypeError('remoteBind port must be in 1..65535')
  }
  const sshHost = String(value('sshHost') ?? '').trim() || domain
  const sshUser = String(value('sshUser') ?? '').trim() || undefined
  const sshPort = Number(value('sshPort') ?? 22)
  if (!Number.isInteger(sshPort) || sshPort < 1 || sshPort > 65535) {
    throw new TypeError('sshPort must be an integer in 1..65535')
  }
  const identityFile = String(value('identityFile') ?? '').trim() || undefined

  return {
    mode,
    domain,
    publicUrl: `https://${domain}/`,
    relayUrl,
    instanceId,
    instanceToken,
    tokenFile,
    settingsFile: settingsFile(),
    localHost,
    localPort,
    extraTrustedHosts: Array.isArray(value('extraTrustedHosts')) ? value('extraTrustedHosts') : [],
    pairingEnabled: value('pairingEnabled') !== false,
    remoteBind,
    sshHost,
    sshUser,
    sshPort,
    identityFile,
  }
}
