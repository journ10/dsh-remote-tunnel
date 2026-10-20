#!/usr/bin/env node
/**
 * Relay administration CLI.
 *
 *   docker compose exec relay node src/cli.js instance list
 *   docker compose exec relay node src/cli.js instance add laptop
 *
 * The CLI opens the same SQLite database as the running relay. SQLite WAL
 * allows the running process to observe new rows immediately.
 */
import { randomBytes } from 'node:crypto'
import path from 'node:path'
import { Store } from './store.js'

const DATA_DIR = process.env.DATA_DIR ?? './data'
const INSTANCE_BASE_DOMAIN = (process.env.INSTANCE_BASE_DOMAIN ?? process.env.PUBLIC_HOST ?? '').trim().toLowerCase()

function fail(message) {
  console.error(`error: ${message}`)
  process.exit(1)
}

function token() {
  return randomBytes(32).toString('hex')
}

function parseOptions(args) {
  const options = {}
  const rest = []
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i]
    if (arg === '--public-host' || arg === '--token') {
      const value = args[i + 1]
      if (value === undefined) fail(`${arg} needs a value`)
      options[arg.slice(2)] = value
      i += 1
    } else {
      rest.push(arg)
    }
  }
  return { options, rest }
}

function validId(id) {
  return /^[a-z0-9][a-z0-9-]{0,31}$/u.test(id)
}

function openStore() {
  return new Store(path.join(DATA_DIR, 'relay.sqlite'))
}

async function claimEnrollment(code) {
  const token = process.env.BOOTSTRAP_INSTANCE_TOKEN?.trim()
  if (token === undefined || token === '') fail('BOOTSTRAP_INSTANCE_TOKEN is not set in the relay container')
  const port = process.env.PORT?.trim() || '8080'
  const response = await fetch(`http://127.0.0.1:${port}/api/enroll/claim`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, token }),
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok || body.ok === false) {
    fail(body.error ?? `enrollment claim failed (HTTP ${String(response.status)})`)
  }
  console.log(JSON.stringify(body))
}

async function main() {
  const [, , group, action, ...rest] = process.argv
  if (group === undefined || group === '-h' || group === '--help') {
    console.log(`Usage:
  node src/cli.js enroll <code>
  node src/cli.js instance list
  node src/cli.js instance add <id> [--public-host <host>] [--token <token>]
  node src/cli.js instance revoke <id>
  node src/cli.js device list <instance-id>
  node src/cli.js device revoke <instance-id> <device-id>

Environment:
  DATA_DIR              relay data directory (default: ./data)
  INSTANCE_BASE_DOMAIN  base domain for <id>.<base> public hosts
  PUBLIC_HOST           fallback base domain
`)
    return
  }

  if (group === 'enroll') {
    if (action === undefined || action === '') fail('usage: enroll <code>')
    await claimEnrollment(action)
    return
  }

  const store = openStore()
  try {
    if (group === 'instance' && action === 'list') {
      const rows = store.listInstances()
      if (rows.length === 0) console.log('no instances')
      for (const row of rows) {
        console.log(`${row.id}\t${row.public_host}\t${row.revoked_at === null ? 'active' : 'revoked'}\t${new Date(row.last_seen_at).toISOString()}`)
      }
      return
    }
    if (group === 'instance' && action === 'add') {
      const { options, rest: args } = parseOptions(rest)
      const id = args[0]
      if (id === undefined || !validId(id)) fail('instance id must be 1-32 characters of a-z, 0-9, or hyphen')
      const base = INSTANCE_BASE_DOMAIN
      const publicHost = (options['public-host'] ?? (base === '' ? '' : `${id}.${base}`)).trim().toLowerCase()
      if (publicHost === '') fail('--public-host is required when INSTANCE_BASE_DOMAIN/PUBLIC_HOST is unset')
      const instanceToken = options.token ?? token()
      store.upsertInstance({ id, token: instanceToken, publicHost })
      store.reviveInstance(id)
      console.log(JSON.stringify({ id, publicHost, token: instanceToken }, null, 2))
      console.error('\nStore the token in the DSH plugin token file (mode 0600).')
      return
    }
    if (group === 'instance' && action === 'revoke') {
      const id = rest[0]
      if (id === undefined) fail('instance id is required')
      store.revokeInstance(id)
      console.log(`revoked ${id}; restart the relay to drop an active tunnel`)
      return
    }
    if (group === 'device' && action === 'list') {
      const id = rest[0]
      if (id === undefined) fail('instance id is required')
      const rows = store.listDevices(id, { includeRevoked: true })
      if (rows.length === 0) console.log('no devices')
      for (const row of rows) {
        console.log(`${row.id}\t${row.name}\t${row.revoked_at === null ? 'active' : 'revoked'}\t${new Date(row.last_seen_at).toISOString()}`)
      }
      return
    }
    if (group === 'device' && action === 'revoke') {
      const [instanceId, deviceId] = rest
      if (instanceId === undefined || deviceId === undefined) fail('usage: device revoke <instance-id> <device-id>')
      const revoked = store.revokeDevice(deviceId, instanceId)
      console.log(revoked ? `revoked ${deviceId}` : `device not found or already revoked: ${deviceId}`)
      return
    }
    fail(`unknown command: ${[group, action].filter(Boolean).join(' ')}`)
  } finally {
    store.close()
  }
}

main().catch((error) => { fail(error instanceof Error ? error.message : String(error)) })
