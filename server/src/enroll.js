/**
 * One-time enrollment channel.
 *
 * The plugin starts a short-lived code, the server-side CLI claims it with the
 * instance token, and the plugin takes the token once. Only hashes of codes are
 * kept; tokens live in memory and are deleted after the first successful take.
 */
import { createHash, timingSafeEqual } from 'node:crypto'
import { RateLimiter } from './rate-limit.js'

const CODE_PATTERN = /^[A-Z2-9]{8,16}$/u
const DEFAULT_TTL_MS = 5 * 60 * 1000
const MAX_PENDING = 500

/**
 * @param {string} code
 * @returns {string}
 */
function hashCode(code) {
  return createHash('sha256').update(code, 'utf8').digest('hex')
}

export class EnrollmentError extends Error {
  /**
   * @param {string} message
   * @param {number} status
   */
  constructor(message, status = 400) {
    super(message)
    this.status = status
  }
}

export class EnrollmentStore {
  /**
   * @param {{store: import('./store.js').Store, logger: any, ttlMs?: number}} options
   */
  constructor({ store, logger, ttlMs = DEFAULT_TTL_MS }) {
    this.store = store
    this.logger = logger
    this.ttlMs = ttlMs
    /** @type {Map<string, {instanceId: string, expiresAt: number, token: string|null, createdAt: number}>} */
    this.pending = new Map()
    this.startLimiter = new RateLimiter(30)
    this.claimLimiter = new RateLimiter(30)
    this.takeLimiter = new RateLimiter(120)
  }

  /**
   * @param {{code: string, instanceId: string, ip: string}} input
   * @returns {{expiresAt: number}}
   */
  start({ code, instanceId, ip }) {
    if (!this.startLimiter.take(ip)) throw new EnrollmentError('too many enrollment starts', 429)
    const normalizedCode = code.trim().toUpperCase()
    if (!CODE_PATTERN.test(normalizedCode)) throw new EnrollmentError('invalid enrollment code', 400)
    const instance = this.store.getInstance(instanceId)
    if (instance === undefined || instance.revoked_at !== null) {
      throw new EnrollmentError('unknown instance', 404)
    }
    this.sweep()
    if (this.pending.size >= MAX_PENDING) throw new EnrollmentError('too many pending enrollments', 429)
    const expiresAt = Date.now() + this.ttlMs
    this.pending.set(hashCode(normalizedCode), {
      instanceId,
      expiresAt,
      token: null,
      createdAt: Date.now(),
    })
    this.store.audit('enroll.start', { instanceId, ip })
    this.logger.info('enrollment started', { instanceId })
    return { expiresAt }
  }

  /**
   * @param {{code: string, token: string, ip: string}} input
   * @returns {{instanceId: string}}
   */
  claim({ code, token, ip }) {
    if (!this.claimLimiter.take(ip)) throw new EnrollmentError('too many enrollment claims', 429)
    const entry = this.lookup(code)
    const instance = this.store.authenticateInstance(entry.instanceId, token)
    if (instance === undefined) {
      this.store.audit('enroll.claim_failed', { instanceId: entry.instanceId, ip })
      throw new EnrollmentError('instance token is invalid', 401)
    }
    entry.token = token
    this.store.audit('enroll.claim', { instanceId: entry.instanceId, ip })
    this.logger.info('enrollment claimed', { instanceId: entry.instanceId })
    return { instanceId: entry.instanceId }
  }

  /**
   * @param {{code: string, ip: string}} input
   * @returns {{instanceId: string, token: string}|undefined} undefined while pending
   */
  take({ code, ip }) {
    if (!this.takeLimiter.take(ip)) throw new EnrollmentError('too many enrollment polls', 429)
    const hash = hashCode(code.trim().toUpperCase())
    const entry = this.pending.get(hash)
    if (entry === undefined) throw new EnrollmentError('enrollment code is unknown or expired', 404)
    if (entry.expiresAt <= Date.now()) {
      this.pending.delete(hash)
      throw new EnrollmentError('enrollment code is unknown or expired', 404)
    }
    if (entry.token === null) return undefined
    this.pending.delete(hash)
    this.store.audit('enroll.take', { instanceId: entry.instanceId, ip })
    return { instanceId: entry.instanceId, token: entry.token }
  }

  /**
   * @param {string} code
   * @returns {{instanceId: string, expiresAt: number, token: string|null, createdAt: number}}
   */
  lookup(code) {
    const normalized = code.trim().toUpperCase()
    if (!CODE_PATTERN.test(normalized)) throw new EnrollmentError('invalid enrollment code', 400)
    const hash = hashCode(normalized)
    const entry = this.pending.get(hash)
    if (entry === undefined) throw new EnrollmentError('enrollment code is unknown or expired', 404)
    if (entry.expiresAt <= Date.now()) {
      this.pending.delete(hash)
      throw new EnrollmentError('enrollment code is unknown or expired', 404)
    }
    return entry
  }

  sweep(now = Date.now()) {
    for (const [hash, entry] of this.pending) {
      if (entry.expiresAt <= now) this.pending.delete(hash)
    }
  }

  /**
   * Constant-time compare used by callers that need to compare codes.
   * @param {string} a
   * @param {string} b
   * @returns {boolean}
   */
  static sameCode(a, b) {
    const left = Buffer.from(a)
    const right = Buffer.from(b)
    return left.length === right.length && timingSafeEqual(left, right)
  }
}
