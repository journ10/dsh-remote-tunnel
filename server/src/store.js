/** SQLite persistence for instances, pairing codes, devices, and audit rows. */

import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { DatabaseSync } from 'node:sqlite'

const CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'

/**
 * @param {string} value
 * @returns {string}
 */
export function hashSecret(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

/**
 * Constant-time comparison of two hex digests.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
export function equalHash(a, b) {
  if (a.length !== b.length) return false
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'))
}

/**
 * @param {number} length
 * @returns {string}
 */
export function generatePairCode(length) {
  let code = ''
  for (let i = 0; i < length; i += 1) {
    code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]
  }
  return code
}

/**
 * @param {number} bytes
 * @returns {string}
 */
export function randomId(bytes = 16) {
  return randomBytes(bytes).toString('base64')
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '')
}

/**
 * @typedef {object} InstanceRow
 * @property {string} id
 * @property {string} public_host
 * @property {string} token_hash
 * @property {number} created_at
 * @property {number} last_seen_at
 * @property {number|null} revoked_at
 */

/**
 * @typedef {object} DeviceRow
 * @property {string} id
 * @property {string} instance_id
 * @property {string} name
 * @property {string|null} user_agent
 * @property {number} created_at
 * @property {number} last_seen_at
 * @property {number|null} revoked_at
 */

export class Store {
  /**
   * @param {string} file - database path, or `:memory:`.
   */
  constructor(file) {
    if (file !== ':memory:') mkdirSync(path.dirname(file), { recursive: true })
    this.db = new DatabaseSync(file)
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA foreign_keys = ON;
      PRAGMA busy_timeout = 5000;
      CREATE TABLE IF NOT EXISTS instances (
        id TEXT PRIMARY KEY,
        public_host TEXT NOT NULL UNIQUE,
        token_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        last_seen_at INTEGER NOT NULL,
        revoked_at INTEGER
      );
      CREATE TABLE IF NOT EXISTS pair_codes (
        code_hash TEXT PRIMARY KEY,
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS pair_codes_instance ON pair_codes(instance_id, expires_at);
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        user_agent TEXT,
        created_at INTEGER NOT NULL,
        last_seen_at INTEGER NOT NULL,
        revoked_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS devices_instance ON devices(instance_id, revoked_at);
      CREATE TABLE IF NOT EXISTS audit (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        at INTEGER NOT NULL,
        event TEXT NOT NULL,
        instance_id TEXT,
        device_id TEXT,
        ip TEXT,
        detail TEXT
      );
      CREATE INDEX IF NOT EXISTS audit_at ON audit(at);
    `)
  }

  close() {
    this.db.close()
  }

  /** Remove expired pairing codes; safe to call periodically. */
  purgePairCodes(now = Date.now()) {
    this.db.prepare('DELETE FROM pair_codes WHERE expires_at <= ? OR used_at IS NOT NULL').run(now)
  }

  /**
   * Create or update an instance.
   * @param {{id: string, token: string, publicHost: string}} input
   * @returns {InstanceRow}
   */
  upsertInstance({ id, token, publicHost }) {
    const now = Date.now()
    const hash = hashSecret(token)
    this.db.prepare(`
      INSERT INTO instances (id, public_host, token_hash, created_at, last_seen_at, revoked_at)
      VALUES (?, ?, ?, ?, ?, NULL)
      ON CONFLICT(id) DO UPDATE SET
        public_host = excluded.public_host,
        token_hash = excluded.token_hash
    `).run(id, publicHost, hash, now, now)
    const row = this.getInstance(id)
    if (row === undefined) throw new Error(`instance ${id} disappeared during upsert`)
    return row
  }

  /**
   * @param {string} id
   * @returns {InstanceRow|undefined}
   */
  getInstance(id) {
    return /** @type {InstanceRow|undefined} */ (this.db
      .prepare('SELECT * FROM instances WHERE id = ?').get(id))
  }

  /**
   * @param {string} host
   * @returns {InstanceRow|undefined}
   */
  getInstanceByHost(host) {
    return /** @type {InstanceRow|undefined} */ (this.db
      .prepare('SELECT * FROM instances WHERE public_host = ?').get(host.toLowerCase()))
  }

  /**
   * @returns {InstanceRow[]}
   */
  listInstances() {
    return /** @type {InstanceRow[]} */ (this.db
      .prepare('SELECT * FROM instances ORDER BY created_at').all())
  }

  /**
   * Verify an instance token without leaking timing information.
   * @param {string} id
   * @param {string} token
   * @returns {InstanceRow|undefined}
   */
  authenticateInstance(id, token) {
    const row = this.getInstance(id)
    if (row === undefined || row.revoked_at !== null) return undefined
    if (!equalHash(row.token_hash, hashSecret(token))) return undefined
    return row
  }

  touchInstance(id, now = Date.now()) {
    this.db.prepare('UPDATE instances SET last_seen_at = ? WHERE id = ?').run(now, id)
  }

  revokeInstance(id) {
    this.db.prepare('UPDATE instances SET revoked_at = ? WHERE id = ?').run(Date.now(), id)
  }

  /**
   * Clear a previous revocation, for an explicit `instance add`.
   * @param {string} id
   */
  reviveInstance(id) {
    this.db.prepare('UPDATE instances SET revoked_at = NULL WHERE id = ?').run(id)
  }

  /**
   * Create a one-time pairing code. Only the hash is stored.
   * @param {string} instanceId
   * @param {number} ttlMs
   * @param {number} length
   * @param {number} [now]
   * @returns {{code: string, expiresAt: number}}
   */
  createPairCode(instanceId, ttlMs, length, now = Date.now()) {
    const instance = this.getInstance(instanceId)
    if (instance === undefined) throw new Error(`unknown instance: ${instanceId}`)
    this.purgePairCodes(now)
    let code = generatePairCode(length)
    let hash = hashSecret(code)
    let attempts = 0
    while (this.db.prepare('SELECT 1 FROM pair_codes WHERE code_hash = ?').get(hash) !== undefined) {
      attempts += 1
      if (attempts > 8) throw new Error('could not allocate a unique pairing code')
      code = generatePairCode(length)
      hash = hashSecret(code)
    }
    const expiresAt = now + ttlMs
    this.db.prepare(`
      INSERT INTO pair_codes (code_hash, instance_id, created_at, expires_at, used_at)
      VALUES (?, ?, ?, ?, NULL)
    `).run(hash, instanceId, now, expiresAt)
    return { code, expiresAt }
  }

  /**
   * Consume a pairing code atomically.
   * @param {string} code
   * @param {number} [now]
   * @returns {InstanceRow|undefined}
   */
  consumePairCode(code, now = Date.now()) {
    const hash = hashSecret(code.trim().toUpperCase())
    this.db.exec('BEGIN IMMEDIATE')
    try {
      const row = /** @type {{instance_id: string}|undefined} */ (this.db.prepare(`
        SELECT instance_id FROM pair_codes
        WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?
      `).get(hash, now))
      if (row === undefined) {
        this.db.exec('COMMIT')
        return undefined
      }
      this.db.prepare('UPDATE pair_codes SET used_at = ? WHERE code_hash = ?').run(now, hash)
      this.db.exec('COMMIT')
      const instance = this.getInstance(row.instance_id)
      if (instance === undefined || instance.revoked_at !== null) return undefined
      return instance
    } catch (error) {
      this.db.exec('ROLLBACK')
      throw error
    }
  }

  /**
   * @param {{instanceId: string, name: string, userAgent?: string}} input
   * @returns {DeviceRow}
   */
  createDevice({ instanceId, name, userAgent }) {
    const now = Date.now()
    const id = randomId(18)
    this.db.prepare(`
      INSERT INTO devices (id, instance_id, name, user_agent, created_at, last_seen_at, revoked_at)
      VALUES (?, ?, ?, ?, ?, ?, NULL)
    `).run(id, instanceId, name, userAgent ?? null, now, now)
    const row = this.getDevice(id)
    if (row === undefined) throw new Error('device insert failed')
    return row
  }

  /**
   * @param {string} id
   * @returns {DeviceRow|undefined}
   */
  getDevice(id) {
    return /** @type {DeviceRow|undefined} */ (this.db
      .prepare('SELECT * FROM devices WHERE id = ?').get(id))
  }

  /**
   * @param {string} instanceId
   * @param {{includeRevoked?: boolean}} [options]
   * @returns {DeviceRow[]}
   */
  listDevices(instanceId, { includeRevoked = false } = {}) {
    const sql = includeRevoked
      ? 'SELECT * FROM devices WHERE instance_id = ? ORDER BY created_at DESC'
      : 'SELECT * FROM devices WHERE instance_id = ? AND revoked_at IS NULL ORDER BY created_at DESC'
    return /** @type {DeviceRow[]} */ (this.db.prepare(sql).all(instanceId))
  }

  /**
   * Verify that a live device belongs to the instance named by a cookie.
   * @param {string} deviceId
   * @param {string} instanceId
   * @returns {DeviceRow|undefined}
   */
  authenticateDevice(deviceId, instanceId) {
    const row = this.getDevice(deviceId)
    if (row === undefined || row.revoked_at !== null || row.instance_id !== instanceId) return undefined
    return row
  }

  touchDevice(id, now = Date.now()) {
    this.db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(now, id)
  }

  /**
   * Revoke one device. The instance id scopes the write so a device id from
   * another instance cannot be revoked by mistake.
   * @param {string} deviceId
   * @param {string} instanceId
   * @returns {boolean}
   */
  revokeDevice(deviceId, instanceId) {
    const result = this.db.prepare(
      'UPDATE devices SET revoked_at = ? WHERE id = ? AND instance_id = ? AND revoked_at IS NULL',
    ).run(Date.now(), deviceId, instanceId)
    return result.changes > 0
  }

  /**
   * @param {string} event
   * @param {{instanceId?: string, deviceId?: string, ip?: string, detail?: object|string}} [fields]
   */
  audit(event, { instanceId, deviceId, ip, detail } = {}) {
    this.db.prepare(`
      INSERT INTO audit (at, event, instance_id, device_id, ip, detail)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(
      Date.now(),
      event,
      instanceId ?? null,
      deviceId ?? null,
      ip ?? null,
      detail === undefined ? null : typeof detail === 'string' ? detail : JSON.stringify(detail),
    )
  }

  /**
   * @param {number} [limit]
   * @returns {object[]}
   */
  recentAudit(limit = 100) {
    return /** @type {object[]} */ (this.db
      .prepare('SELECT * FROM audit ORDER BY id DESC LIMIT ?').all(limit))
  }
}
