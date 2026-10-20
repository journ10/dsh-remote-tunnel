/** Legacy SSH reverse-tunnel supervisor (`mode: ssh`). */

const MIN_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 30_000
const STABLE_AFTER_MS = 10_000
const UP_AFTER_MS = 3_000

/**
 * @typedef {import('./config.js').ResolvedConfig} ResolvedConfig
 */

/**
 * Build the ssh argv for one reverse tunnel.
 * @param {string} program
 * @param {ResolvedConfig} settings
 * @param {string} target
 * @returns {string[]}
 */
export function sshArgv(program, settings, target) {
  const argv = [
    program,
    '-N',
    '-T',
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'BatchMode=yes',
    '-o', 'ConnectTimeout=10',
    '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3',
  ]
  if (settings.sshPort !== 22) argv.push('-p', String(settings.sshPort))
  if (settings.identityFile !== undefined) argv.push('-i', settings.identityFile)
  argv.push('-R', `${settings.remoteBind}:127.0.0.1:${settings.localPort}`, target)
  return argv
}

/**
 * @param {unknown} error
 * @returns {string}
 */
function messageOf(error) {
  return error instanceof Error ? error.message : String(error)
}

/**
 * Start and supervise the SSH tunnel. The caller owns the fiber effect.
 * @param {{subprocess: any, logger: any}} ctx
 * @param {ResolvedConfig} settings
 * @returns {{close: () => void, status: () => {connected: boolean, lastError: string}}}
 */
export function startSsh(ctx, settings) {
  const target = settings.sshUser === undefined
    ? settings.sshHost
    : `${settings.sshUser}@${settings.sshHost}`

  let stopped = false
  let child
  let retryTimer
  let upTimer
  let backoff = MIN_BACKOFF_MS
  let connected = false
  let lastError = ''

  const clearTimers = () => {
    if (retryTimer !== undefined) {
      clearTimeout(retryTimer)
      retryTimer = undefined
    }
    if (upTimer !== undefined) {
      clearTimeout(upTimer)
      upTimer = undefined
    }
  }

  const scheduleRestart = () => {
    if (stopped) return
    connected = false
    const delay = backoff
    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
    ctx.logger.warn(`dsh-remote-tunnel: reconnecting ssh in ${delay}ms`)
    retryTimer = setTimeout(() => {
      retryTimer = undefined
      void start()
    }, delay)
    retryTimer.unref?.()
  }

  const start = async () => {
    if (stopped) return
    clearTimers()
    let program
    try {
      program = await ctx.subprocess.resolveExecutable('ssh')
    } catch (error) {
      if (stopped) return
      lastError = `cannot find ssh: ${messageOf(error)}`
      ctx.logger.warn(`dsh-remote-tunnel: ${lastError}`)
      scheduleRestart()
      return
    }
    if (stopped) return

    const startedAt = Date.now()
    let handle
    try {
      handle = ctx.subprocess.spawn({
        argv: sshArgv(program, settings, target),
        cwd: process.cwd(),
        env: process.env.SSH_AUTH_SOCK === undefined
          ? undefined
          : { SSH_AUTH_SOCK: process.env.SSH_AUTH_SOCK },
        stdio: {
          stdin: 'ignore',
          stdout: { maxBytes: 64 * 1024 },
          stderr: { maxBytes: 64 * 1024 },
        },
        graceMs: 5_000,
      })
    } catch (error) {
      if (stopped) return
      lastError = `cannot start ssh: ${messageOf(error)}`
      ctx.logger.warn(`dsh-remote-tunnel: ${lastError}`)
      scheduleRestart()
      return
    }

    child = handle
    lastError = ''
    ctx.logger.info(`dsh-remote-tunnel: ssh ${target} -> ${settings.remoteBind}`)
    upTimer = setTimeout(() => {
      upTimer = undefined
      if (!stopped) {
        connected = true
        ctx.logger.info(`dsh-remote-tunnel: ssh tunnel up at https://${settings.domain}/`)
      }
    }, UP_AFTER_MS)
    upTimer.unref?.()

    handle.done.then((outcome) => {
      if (child === handle) child = undefined
      clearTimers()
      connected = false
      if (stopped) return
      const stderr = handle.collected.stderr.readFrom(0).text.trim()
      const reason = outcome.exitCode === null ? String(outcome.signal) : `exit ${outcome.exitCode}`
      lastError = `ssh stopped (${reason})${stderr === '' ? '' : `: ${stderr}`}`
      ctx.logger.warn(`dsh-remote-tunnel: ${lastError}`)
      if (Date.now() - startedAt >= STABLE_AFTER_MS) backoff = MIN_BACKOFF_MS
      scheduleRestart()
    }, (error) => {
      if (child === handle) child = undefined
      clearTimers()
      connected = false
      if (stopped) return
      lastError = `ssh failed: ${messageOf(error)}`
      ctx.logger.warn(`dsh-remote-tunnel: ${lastError}`)
      scheduleRestart()
    })
  }

  void start()

  return {
    close() {
      stopped = true
      clearTimers()
      connected = false
      const handle = child
      child = undefined
      if (handle === undefined) return
      handle.terminate()
      void handle.waitForExit().catch(() => undefined)
    },
    status() {
      return { connected, lastError }
    },
  }
}
