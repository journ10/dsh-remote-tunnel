/**
 * Host half of `dsh-remote-tunnel`.
 *
 * Relay mode keeps one outbound WebSocket to a self-hosted HTTPS relay, proxies
 * multiplexed HTTP/WebSocket streams to the local DSH server, and injects a DSH
 * browser cookie minted on this machine. SSH mode retains the legacy reverse
 * tunnel. The bundle patch writes the public host into
 * `connection.trustedHosts` and `web-runtime.publicUrl`; both are
 * composition-time values and need a DSH restart when they change.
 *
 * Relay URL, instance id, and the token can be changed from the Plugins page.
 * The runtime is restarted in place; only a domain change needs a DSH restart.
 * @module dsh-remote-tunnel
 */

import { Config, resolveConfig } from './config.js'
import { registerRoutes } from './routes.js'
import { TunnelRuntime } from './runtime.js'

/** Loader row name. */
export const name = 'dsh-remote-tunnel'

/** The shared child-process seam owns the legacy ssh process. */
export const inject = ['subprocess']

export { Config }

/**
 * @param {any} ctx
 * @param {any} config
 */
export function apply(ctx, config) {
  const logger = ctx.logger
  let settings
  try {
    settings = resolveConfig(config, logger)
  } catch (error) {
    logger.error(`dsh-remote-tunnel: invalid configuration: ${error instanceof Error ? error.message : String(error)}`)
    throw error
  }

  const runtime = new TunnelRuntime({ ctx, settings, logger })
  ctx.effect(() => {
    runtime.start()
    return () => runtime.close()
  }, 'dsh-remote-tunnel: transport runtime')

  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => registerRoutes(webCtx, {
      runtime,
      logger,
      getConfig: () => resolveConfig(config, logger),
    }), 'dsh-remote-tunnel: plugin routes')
  })
}
