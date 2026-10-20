/**
 * Transport runtime: owns the active SSH supervisor or relay client and can be
 * reconfigured from the Plugins page without restarting DSH.
 */

import { DshProxy } from './dsh-proxy.js'
import { RelayClient } from './relay-client.js'
import { startSsh } from './ssh.js'

export class TunnelRuntime {
  /**
   * @param {{ctx: any, settings: import('./config.js').ResolvedConfig, logger: any}} options
   */
  constructor({ ctx, settings, logger }) {
    this.ctx = ctx
    this.settings = settings
    this.logger = logger
    this.ssh = undefined
    this.client = undefined
    this.proxy = undefined
    this.configError = ''
    this.stopped = true
  }

  start() {
    this.stopped = false
    this.configError = ''
    if (this.settings.mode === 'ssh') this.startSsh()
    else this.startRelay()
  }

  startSsh() {
    this.ssh = startSsh(this.ctx, this.settings)
    this.logger.info(`dsh-remote-tunnel: ssh mode for ${this.settings.domain}`)
  }

  startRelay() {
    const settings = this.settings
    this.proxy = new DshProxy({
      localHost: settings.localHost,
      localPort: settings.localPort,
      publicHost: settings.domain,
      getTokenUrl: () => {
        const connection = this.ctx.get('connection')
        if (connection === undefined || typeof connection.authenticatedUrl !== 'function') return undefined
        return connection.authenticatedUrl(`http://${settings.localHost}:${String(settings.localPort)}/`)
      },
      logger: this.logger,
    })

    if (settings.relayUrl === '') {
      this.configError = '尚未配置 Relay URL'
    } else if (settings.instanceToken === undefined) {
      this.configError = '尚未填写实例令牌，请在下方“服务器连接”中粘贴'
    } else {
      this.client = new RelayClient({
        url: settings.relayUrl,
        instanceId: settings.instanceId,
        instanceToken: settings.instanceToken,
        publicHost: settings.domain,
        proxy: this.proxy,
        logger: this.logger,
        onState: state => {
          if (state.connected) this.logger.info(`dsh-remote-tunnel: relay connected (${settings.instanceId})`)
        },
      })
      this.client.start()
    }
    if (this.configError !== '') this.logger.warn(`dsh-remote-tunnel: ${this.configError}`)
    this.logger.info(`dsh-remote-tunnel: relay mode for ${settings.domain}`)
  }

  async reconfigure(next) {
    if (next.domain !== this.settings.domain) {
      return { restartRequired: true, reason: 'domain changed' }
    }
    if (next.mode === this.settings.mode
      && next.relayUrl === this.settings.relayUrl
      && next.instanceId === this.settings.instanceId
      && next.instanceToken === this.settings.instanceToken
      && next.localPort === this.settings.localPort) {
      return { restartRequired: false, unchanged: true }
    }
    this.stop()
    this.settings = next
    this.start()
    return { restartRequired: false }
  }

  stop() {
    this.ssh?.close()
    this.ssh = undefined
    this.client?.stop()
    this.client = undefined
    this.proxy?.close()
    this.proxy = undefined
    this.configError = ''
  }

  close() {
    this.stopped = true
    this.stop()
  }

  status() {
    const settings = this.settings
    const base = {
      mode: settings.mode,
      transport: settings.mode,
      publicHost: settings.domain,
      publicUrl: settings.publicUrl,
      relayUrl: settings.relayUrl,
      instanceId: settings.instanceId,
      pairingEnabled: settings.pairingEnabled,
      tokenFile: settings.tokenFile,
      settingsFile: settings.settingsFile,
      tokenConfigured: settings.instanceToken !== undefined,
    }
    if (settings.mode === 'ssh') {
      const ssh = this.ssh?.status() ?? { connected: false, lastError: 'ssh supervisor is not running' }
      return {
        ...base,
        connected: ssh.connected,
        lastError: ssh.lastError,
        devices: [],
      }
    }
    const client = this.client?.status() ?? {
      connected: false,
      lastError: this.configError || 'relay 客户端未运行',
      devices: [],
      streams: 0,
    }
    return {
      ...base,
      ...client,
      configError: this.configError,
      ...this.proxy?.status(),
    }
  }

  async createPairCode(ttlMs) {
    if (this.client === undefined) throw new Error(this.configError || 'relay 未连接')
    return this.client.createPairCode(ttlMs)
  }

  async listDevices() {
    if (this.client === undefined) return []
    return this.client.listDevices()
  }

  async revokeDevice(deviceId) {
    if (this.client === undefined) throw new Error(this.configError || 'relay 未连接')
    return this.client.revokeDevice(deviceId)
  }
}
