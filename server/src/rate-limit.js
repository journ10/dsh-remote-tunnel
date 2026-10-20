/** In-process fixed-window rate limiter. */

export class RateLimiter {
  /**
   * @param {number} limit - allowed hits per window.
   * @param {number} [windowMs]
   */
  constructor(limit, windowMs = 60_000) {
    this.limit = limit
    this.windowMs = windowMs
    /** @type {Map<string, {start: number, count: number}>} */
    this.buckets = new Map()
    this.lastSweep = Date.now()
  }

  /**
   * Record one hit.
   * @param {string} key
   * @param {number} [now]
   * @returns {boolean} true when the hit is allowed.
   */
  take(key, now = Date.now()) {
    if (now - this.lastSweep > this.windowMs) this.sweep(now)
    const bucket = this.buckets.get(key)
    if (bucket === undefined || now - bucket.start >= this.windowMs) {
      this.buckets.set(key, { start: now, count: 1 })
      return true
    }
    if (bucket.count >= this.limit) return false
    bucket.count += 1
    return true
  }

  /**
   * @param {string} key
   * @param {number} [now]
   * @returns {boolean} true when the key is currently blocked.
   */
  blocked(key, now = Date.now()) {
    const bucket = this.buckets.get(key)
    if (bucket === undefined) return false
    if (now - bucket.start >= this.windowMs) return false
    return bucket.count >= this.limit
  }

  sweep(now = Date.now()) {
    this.lastSweep = now
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.start >= this.windowMs) this.buckets.delete(key)
    }
  }
}
