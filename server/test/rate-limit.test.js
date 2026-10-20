import assert from 'node:assert/strict'
import test from 'node:test'
import { RateLimiter } from '../src/rate-limit.js'

test('rate limiter allows a burst and refills after the window', () => {
  const limiter = new RateLimiter(2, 1_000)
  assert.equal(limiter.take('ip', 0), true)
  assert.equal(limiter.take('ip', 1), true)
  assert.equal(limiter.take('ip', 2), false)
  assert.equal(limiter.blocked('ip', 3), true)
  assert.equal(limiter.take('ip', 1_001), true)
  assert.equal(limiter.blocked('ip', 1_002), false)
  assert.equal(limiter.take('other', 1_002), true)
})
