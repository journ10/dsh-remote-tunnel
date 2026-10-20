/** Relay process entry point. */

import { createServer } from 'node:http'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { loadConfig } from './config.js'
import { EnrollmentStore } from './enroll.js'
import { createLogger } from './log.js'
import { Proxy } from './proxy.js'
import { Store } from './store.js'
import { TunnelHub } from './tunnel.js'

/**
 * Build the relay without listening. Tests use this with a random port.
 * @param {{config: import('./config.js').RelayConfig, logger?: ReturnType<import('./log.js').createLogger>, store?: Store}} options
 */
export function createRelay({ config, logger = createLogger(config.logLevel), store = undefined }) {
  const db = store ?? new Store(path.join(config.dataDir, 'relay.sqlite'))
  if (config.bootstrap !== undefined) {
    const instance = db.upsertInstance({
      id: config.bootstrap.id,
      token: config.bootstrap.token,
      publicHost: config.bootstrap.publicHost ?? `${config.bootstrap.id}.${config.instanceBaseDomain}`,
    })
    logger.info('bootstrap instance configured', { instanceId: instance.id, publicHost: instance.public_host })
  } else if (db.listInstances().length === 0) {
    logger.warn('no instances configured; set BOOTSTRAP_INSTANCE_ID and BOOTSTRAP_INSTANCE_TOKEN')
  }
  db.purgePairCodes()

  const hub = new TunnelHub({ store: db, config, logger })
  const enroll = new EnrollmentStore({ store: db, logger })
  const proxy = new Proxy({ store: db, hub, config, logger, enroll })
  const server = createServer((req, res) => {
    void proxy.handle(req, res)
  })
  server.headersTimeout = 30_000
  server.requestTimeout = 0
  server.keepAliveTimeout = 65_000
  server.maxHeadersCount = 100
  server.on('upgrade', (req, socket, head) => {
    const pathname = new URL(req.url ?? '/', 'http://relay.invalid').pathname
    if (pathname === '/tunnel') hub.handleUpgrade(req, socket, head)
    else proxy.handleUpgrade(req, socket, head)
  })
  server.on('clientError', (error, socket) => {
    logger.debug('client error', { message: error.message })
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
  })

  const purgeTimer = setInterval(() => {
    try { db.purgePairCodes() } catch (error) {
      logger.warn('pair-code purge failed', { message: error instanceof Error ? error.message : String(error) })
    }
  }, 10 * 60 * 1000)
  purgeTimer.unref?.()

  let closed = false
  async function close() {
    if (closed) return
    closed = true
    clearInterval(purgeTimer)
    server.close()
    server.closeAllConnections?.()
    await hub.closeAll()
    db.close()
  }

  return { config, logger, store: db, hub, enroll, proxy, server, close }
}

/**
 * Start the relay on its configured port.
 * @param {{config: import('./config.js').RelayConfig, logger?: ReturnType<import('./log.js').createLogger>}} options
 */
export async function startRelay(options) {
  const relay = createRelay(options)
  await new Promise((resolve, reject) => {
    relay.server.once('error', reject)
    relay.server.listen(relay.config.port, '0.0.0.0', () => {
      relay.server.off('error', reject)
      resolve(undefined)
    })
  })
  const address = relay.server.address()
  const port = typeof address === 'object' && address !== null ? address.port : relay.config.port
  relay.logger.info('relay listening', {
    port,
    publicHost: relay.config.publicHost,
    instanceBaseDomain: relay.config.instanceBaseDomain,
    secureCookies: relay.config.secureCookies,
  })
  return { ...relay, port }
}

export async function main() {
  const config = loadConfig()
  const logger = createLogger(config.logLevel)
  const relay = await startRelay({ config, logger })
  let stopping = false
  const stop = async (signal) => {
    if (stopping) return
    stopping = true
    logger.info('stopping relay', { signal })
    const timer = setTimeout(() => process.exit(1), 10_000)
    timer.unref?.()
    await relay.close()
    clearTimeout(timer)
    process.exit(0)
  }
  process.on('SIGTERM', () => { void stop('SIGTERM') })
  process.on('SIGINT', () => { void stop('SIGINT') })
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`[relay] fatal: ${error instanceof Error ? error.message : String(error)}`)
    process.exit(1)
  })
}
