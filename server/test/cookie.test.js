import assert from 'node:assert/strict'
import test from 'node:test'
import {
  DEVICE_COOKIE,
  createDeviceCookie,
  parseCookies,
  serializeDeviceCookie,
  stripDeviceCookie,
  verifyDeviceCookie,
} from '../src/cookie.js'

const SECRET = '0123456789abcdef0123456789abcdef'

test('device cookie round-trips and rejects tampering', () => {
  const value = createDeviceCookie('device-1', 'default', 60_000, SECRET, 1_000)
  const payload = verifyDeviceCookie(value, SECRET, 1_001)
  assert.equal(payload?.d, 'device-1')
  assert.equal(payload?.i, 'default')
  assert.equal(verifyDeviceCookie(`${value}x`, SECRET, 1_001), undefined)
  assert.equal(verifyDeviceCookie(value, `${SECRET}x`, 1_001), undefined)
  assert.equal(verifyDeviceCookie(value, SECRET, 61_001), undefined)
  assert.equal(verifyDeviceCookie('not-a-cookie', SECRET), undefined)
})

test('cookie helpers keep only the device cookie out of the forwarded header', () => {
  assert.deepEqual(parseCookies('a=1; __dsh_device=x; b=2'), { a: '1', __dsh_device: 'x', b: '2' })
  assert.equal(stripDeviceCookie(`a=1; ${DEVICE_COOKIE}=x; b=2`), 'a=1; b=2')
  assert.equal(stripDeviceCookie(`${DEVICE_COOKIE}=x`), undefined)
  const serialized = serializeDeviceCookie('value', 3600, true)
  assert.match(serialized, /^__dsh_device=value; Max-Age=3600; Path=\/; HttpOnly; SameSite=Lax; Secure$/u)
})
