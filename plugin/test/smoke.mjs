import './isolated-home.mjs'

/**
 * Standalone smoke tests for the host supervisor and the client registration.
 * No DSH runtime is required: the subprocess seam, logger, effect, and slots
 * are minimal fakes.
 */
import assert from 'node:assert/strict'
import { Config, apply } from '../lib/index.js'
import { resolveConfig, normalizeRelayUrl, writeSettings, writeTokenFile, defaultTokenFile } from '../lib/config.js'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// ---------------------------------------------------------------------------
// Config: generic defaults, relay URL normalization
// ---------------------------------------------------------------------------
{
  const resolved = Config({
    domain: 'mac.relay.example.com',
    relayUrl: 'https://relay.example.com',
    instanceToken: 't'.repeat(32),
  })
  assert.equal(resolved.domain.get(), 'mac.relay.example.com')
  assert.equal(resolved.mode, 'relay')
  assert.equal(resolved.localPort, 3080)
  assert.equal(resolved.pairingEnabled, true)

  const settings = resolveConfig(resolved)
  assert.equal(settings.relayUrl, 'wss://relay.example.com/tunnel')
  assert.equal(settings.instanceId, 'default')
  assert.equal(normalizeRelayUrl('wss://relay.example.com'), 'wss://relay.example.com/tunnel')
  assert.throws(() => normalizeRelayUrl('ftp://relay.example.com'), /https:\/\/ or wss:\/\//)
  assert.throws(() => resolveConfig({ domain: 'not a host' }), /domain/)
  console.log('config smoke ok')
}

// ---------------------------------------------------------------------------
// Settings file: the Plugins page writes relayUrl/instanceId; the token stays
// in its 0600 file and resolveConfig merges both.
// ---------------------------------------------------------------------------
{
  const previous = process.env.DSH_HOME
  const dir = mkdtempSync(join(tmpdir(), 'dsh-remote-config-'))
  process.env.DSH_HOME = dir
  try {
    writeTokenFile(defaultTokenFile(), 't'.repeat(64))
    writeSettings({ relayUrl: 'https://relay.example.com', instanceId: 'mac' })
    const resolved = Config({ domain: 'mac.relay.example.com' })
    const settings = resolveConfig(resolved)
    assert.equal(settings.relayUrl, 'wss://relay.example.com/tunnel')
    assert.equal(settings.instanceId, 'mac')
    assert.equal(settings.instanceToken, 't'.repeat(64))
    assert.equal(settings.tokenFile, join(dir, 'dsh-remote-tunnel', 'instance-token'))
    assert.equal(settings.settingsFile, join(dir, 'dsh-remote-tunnel', 'settings.json'))
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    rmSync(dir, { recursive: true, force: true })
  }
  console.log('settings smoke ok')
}

// ---------------------------------------------------------------------------
// Legacy SSH mode: argv + restart logging + disposal
// ---------------------------------------------------------------------------
{
  const resolved = Config({
    mode: 'ssh',
    domain: 'dsh.example.com',
    sshHost: 'server.example.com',
    sshUser: 'ubuntu',
    remoteBind: '127.0.0.1:3080',
  })
  const settings = resolveConfig(resolved)
  assert.equal(settings.mode, 'ssh')

  const logs = []
  const disposers = []
  let spawned
  const ctx = {
    logger: {
      info: (...args) => logs.push(['info', ...args]),
      warn: (...args) => logs.push(['warn', ...args]),
      error: (...args) => logs.push(['error', ...args]),
    },
    subprocess: {
      resolveExecutable: async () => '/bin/echo',
      spawn: (spec) => {
        spawned = spec
        return {
          done: Promise.resolve({ exitCode: 1, signal: null }),
          collected: { stderr: { readFrom: () => ({ text: 'simulated failure' }) } },
          terminate() {},
          waitForExit: async () => true,
        }
      },
    },
    effect: (fn) => { disposers.push(fn()) },
    inject: () => {},
  }

  apply(ctx, resolved)
  await new Promise(resolve => setTimeout(resolve, 40))

  assert.ok(spawned, 'ssh was not spawned')
  for (const expected of [
    '-N',
    '-T',
    'BatchMode=yes',
    'ConnectTimeout=10',
    '127.0.0.1:3080:127.0.0.1:3080',
    'ubuntu@server.example.com',
  ]) {
    assert.ok(spawned.argv.includes(expected), `ssh argv is missing ${expected}`)
  }
  assert.ok(
    logs.some(([level, message]) => level === 'warn' && String(message).includes('ssh stopped')),
    'the exit was not logged',
  )

  for (const dispose of disposers) dispose()
  console.log('ssh smoke ok')
}

// ---------------------------------------------------------------------------
// Relay mode: missing token is reported, not thrown at load
// ---------------------------------------------------------------------------
{
  const resolved = Config({
    domain: 'mac.relay.example.com',
    relayUrl: 'wss://relay.example.com/tunnel',
    tokenFile: '/nonexistent/dsh-remote-tunnel-token',
  })
  assert.equal(resolveConfig(resolved).instanceToken, undefined)
  const logs = []
  const ctx = {
    logger: {
      info: (...args) => logs.push(['info', ...args]),
      warn: (...args) => logs.push(['warn', ...args]),
      error: (...args) => logs.push(['error', ...args]),
    },
    effect: (fn) => { fn() },
    inject: () => {},
    get: () => undefined,
  }
  apply(ctx, resolved)
  assert.ok(logs.some(([level, message]) => level === 'warn' && String(message).includes('实例令牌')))
  console.log('relay config smoke ok')
}

// ---------------------------------------------------------------------------
// Client: the Plugins row page registers under the package + row key
// ---------------------------------------------------------------------------
{
  globalThis.window = {
    __ModuleLoader__: {
      load: (definition) => { globalThis.__dshModule = definition },
    },
  }

  await import('../lib/client.js')

  const React = {
    createElement: (...args) => ({ args }),
    useState: (value) => [value, () => {}],
    useEffect: () => {},
  }
  const client = globalThis.__dshModule.factory((name) => {
    if (name === 'react') return React
    throw new Error(`unexpected require: ${name}`)
  })

  const registrations = []
  const ctx = {
    slots: {
      inject: (name, register) => {
        assert.equal(name, 'plugins.row.config')
        register()
      },
      register: (options) => {
        registrations.push(options.key)
        return () => {}
      },
    },
  }

  client.apply(ctx)
  assert.deepEqual(registrations, ['dsh-remote-tunnel#remote-tunnel'])
  assert.deepEqual(client.inject, ['slots'])
  console.log('client smoke ok')
}
