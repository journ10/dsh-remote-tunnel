import assert from 'node:assert/strict'
import test from 'node:test'
import { Store, hashSecret } from '../src/store.js'

test('instances authenticate by token and host', () => {
  const store = new Store(':memory:')
  store.upsertInstance({ id: 'mac', token: 'a'.repeat(32), publicHost: 'mac.relay.test' })
  assert.equal(store.getInstance('mac')?.public_host, 'mac.relay.test')
  assert.equal(store.getInstanceByHost('mac.relay.test')?.id, 'mac')
  assert.equal(store.authenticateInstance('mac', 'a'.repeat(32))?.id, 'mac')
  assert.equal(store.authenticateInstance('mac', 'b'.repeat(32)), undefined)
  assert.equal(store.authenticateInstance('missing', 'a'.repeat(32)), undefined)
  assert.equal(store.getInstance('mac')?.token_hash, hashSecret('a'.repeat(32)))

  store.revokeInstance('mac')
  assert.equal(store.authenticateInstance('mac', 'a'.repeat(32)), undefined)
  store.upsertInstance({ id: 'mac', token: 'a'.repeat(32), publicHost: 'mac.relay.test' })
  assert.equal(store.authenticateInstance('mac', 'a'.repeat(32)), undefined, 'upsert must not revive')
  store.reviveInstance('mac')
  assert.equal(store.authenticateInstance('mac', 'a'.repeat(32))?.id, 'mac')
  store.close()
})

test('pairing codes are one-time, expiring, and instance-scoped', () => {
  const store = new Store(':memory:')
  store.upsertInstance({ id: 'mac', token: 'a'.repeat(32), publicHost: 'mac.relay.test' })
  store.upsertInstance({ id: 'pc', token: 'b'.repeat(32), publicHost: 'pc.relay.test' })
  const { code, expiresAt } = store.createPairCode('mac', 60_000, 10, 1_000)
  assert.equal(code.length, 10)
  assert.equal(expiresAt, 61_000)
  const consumed = store.consumePairCode(code.toLowerCase(), 2_000)
  assert.equal(consumed?.id, 'mac')
  assert.equal(store.consumePairCode(code, 2_000), undefined)

  const expiring = store.createPairCode('mac', 1_000, 10, 1_000)
  assert.equal(store.consumePairCode(expiring.code, 2_001), undefined)
  store.close()
})

test('devices are listed, scoped, and revoked', () => {
  const store = new Store(':memory:')
  store.upsertInstance({ id: 'mac', token: 'a'.repeat(32), publicHost: 'mac.relay.test' })
  store.upsertInstance({ id: 'pc', token: 'b'.repeat(32), publicHost: 'pc.relay.test' })
  const device = store.createDevice({ instanceId: 'mac', name: 'Pixel', userAgent: 'test' })
  assert.equal(store.authenticateDevice(device.id, 'mac')?.name, 'Pixel')
  assert.equal(store.authenticateDevice(device.id, 'pc'), undefined)
  assert.equal(store.revokeDevice(device.id, 'pc'), false)
  assert.equal(store.revokeDevice(device.id, 'mac'), true)
  assert.equal(store.authenticateDevice(device.id, 'mac'), undefined)
  assert.equal(store.listDevices('mac').length, 0)
  assert.equal(store.listDevices('mac', { includeRevoked: true }).length, 1)
  store.audit('device.paired', { instanceId: 'mac', deviceId: device.id, ip: '127.0.0.1' })
  assert.equal(store.recentAudit(1)[0].event, 'device.paired')
  store.close()
})
