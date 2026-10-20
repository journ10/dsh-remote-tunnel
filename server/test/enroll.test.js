import assert from 'node:assert/strict'
import test from 'node:test'
import { EnrollmentError, EnrollmentStore } from '../src/enroll.js'
import { Store } from '../src/store.js'

const TOKEN = 'a'.repeat(64)

function setup() {
  const store = new Store(':memory:')
  store.upsertInstance({ id: 'mac', token: TOKEN, publicHost: 'mac.relay.test' })
  const enroll = new EnrollmentStore({
    store,
    logger: { info() {}, warn() {} },
    ttlMs: 60_000,
  })
  return { store, enroll }
}

test('enrollment code is claimed with the instance token and taken once', () => {
  const { store, enroll } = setup()
  const { expiresAt } = enroll.start({ code: 'ABCDEFGHJK', instanceId: 'mac', ip: '127.0.0.1' })
  assert.ok(expiresAt > Date.now())
  assert.equal(enroll.take({ code: 'ABCDEFGHJK', ip: '127.0.0.1' }), undefined)
  assert.deepEqual(enroll.claim({ code: 'ABCDEFGHJK', token: TOKEN, ip: '127.0.0.1' }), { instanceId: 'mac' })
  assert.deepEqual(enroll.take({ code: 'ABCDEFGHJK', ip: '127.0.0.1' }), { instanceId: 'mac', token: TOKEN })
  assert.throws(() => enroll.take({ code: 'ABCDEFGHJK', ip: '127.0.0.1' }), (error) => error instanceof EnrollmentError && error.status === 404)
  store.close()
})

test('enrollment rejects bad tokens, unknown codes, and expired codes', () => {
  const { store, enroll } = setup()
  enroll.start({ code: 'ABCDEFGHJK', instanceId: 'mac', ip: '127.0.0.1' })
  assert.throws(() => enroll.claim({ code: 'ABCDEFGHJK', token: 'b'.repeat(64), ip: '127.0.0.1' }), (error) => error instanceof EnrollmentError && error.status === 401)
  assert.throws(() => enroll.claim({ code: 'ZZZZZZZZZZ', token: TOKEN, ip: '127.0.0.1' }), (error) => error instanceof EnrollmentError && error.status === 404)
  assert.throws(() => enroll.start({ code: 'bad', instanceId: 'mac', ip: '127.0.0.1' }), (error) => error instanceof EnrollmentError && error.status === 400)

  enroll.start({ code: 'JKLMNPQRST', instanceId: 'mac', ip: '127.0.0.1' })
  const entry = enroll.lookup('JKLMNPQRST')
  entry.expiresAt = Date.now() - 1
  assert.throws(() => enroll.take({ code: 'JKLMNPQRST', ip: '127.0.0.1' }), (error) => error instanceof EnrollmentError && error.status === 404)
  store.close()
})
