import assert from 'node:assert/strict'
import test from 'node:test'
import { loadConfig, normalizeHost } from '../src/config.js'

const BASE = {
  PUBLIC_HOST: 'relay.test',
  COOKIE_SECRET: '0123456789abcdef0123456789abcdef',
}

test('config derives instance hosts and secure cookies from the public URL', () => {
  const config = loadConfig({ ...BASE })
  assert.equal(config.publicHost, 'relay.test')
  assert.equal(config.instanceBaseDomain, 'relay.test')
  assert.equal(config.publicUrl, 'https://relay.test')
  assert.equal(config.secureCookies, true)
  assert.equal(config.pairCodeLength, 10)
  assert.equal(config.bootstrap, undefined)

  const insecure = loadConfig({
    ...BASE,
    PUBLIC_URL: 'http://relay.test:8080',
    COOKIE_SECURE: 'false',
    INSTANCE_BASE_DOMAIN: 'dsh.test',
  })
  assert.equal(insecure.publicUrl, 'http://relay.test:8080')
  assert.equal(insecure.secureCookies, false)
  assert.equal(insecure.instanceBaseDomain, 'dsh.test')
})

test('config validates hosts, secrets, and bootstrap values', () => {
  assert.equal(normalizeHost(' Relay.Example.COM. '), 'relay.example.com')
  assert.throws(() => normalizeHost('https://relay.example.com'), /bare DNS name/u)
  assert.throws(() => loadConfig({ ...BASE, COOKIE_SECRET: 'short' }), /at least 32/u)
  assert.throws(() => loadConfig({ ...BASE, PUBLIC_HOST: '' }), /PUBLIC_HOST is required/u)
  assert.throws(() => loadConfig({ ...BASE, BOOTSTRAP_INSTANCE_ID: 'mac' }), /BOOTSTRAP_INSTANCE_TOKEN/u)

  const boot = loadConfig({
    ...BASE,
    BOOTSTRAP_INSTANCE_ID: 'mac',
    BOOTSTRAP_INSTANCE_TOKEN: 't'.repeat(32),
  })
  assert.equal(boot.bootstrap?.publicHost, 'mac.relay.test')
})
