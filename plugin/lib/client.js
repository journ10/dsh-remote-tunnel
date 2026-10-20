/**
 * Browser half of `dsh-remote-tunnel`: Plugins → Configure page with the
 * public host, relay status, a pairing QR code, and paired-device revocation.
 * The page talks to the Host routes under `/remote-tunnel`.
 */

window.__ModuleLoader__.load({
  id: 'dsh-remote-tunnel',
  factory: (require) => {
    const module = { exports: {} }
    const exports = module.exports

    const React = require('react')

    /** Row page key: the bundle package and the row id from the patch. */
    const CONFIG_KEY = 'dsh-remote-tunnel#remote-tunnel'

    /** A bare DNS name, without scheme, port, or path. */
    const DOMAIN_PATTERN = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/i

    const styles = {
      page: { display: 'flex', flexDirection: 'column', gap: '14px', maxWidth: '640px' },
      card: {
        border: '1px solid var(--dsw-alias-border-l2, #e0e0e0)',
        borderRadius: '10px',
        padding: '12px 14px',
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
      },
      row: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', flexWrap: 'wrap' },
      label: { fontSize: '13px', fontWeight: 600, color: 'var(--dsw-alias-label-primary, #1f1f1f)' },
      input: {
        width: '100%', boxSizing: 'border-box', padding: '8px 10px',
        border: '1px solid var(--dsw-alias-border-l2, #d0d0d0)', borderRadius: '8px',
        background: 'var(--dsw-alias-bg-layer-1, #fff)', color: 'inherit', font: 'inherit',
      },
      hint: { margin: 0, fontSize: '12px', lineHeight: '18px', color: 'var(--dsw-alias-label-secondary, #8b8b8b)' },
      mono: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '12px' },
      actions: { display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' },
      button: {
        padding: '6px 14px', borderRadius: '8px', border: '1px solid var(--dsw-alias-border-l2, #d0d0d0)',
        background: 'var(--dsw-alias-bg-layer-1, #fff)', color: 'inherit', font: 'inherit', cursor: 'pointer',
      },
      primary: {
        padding: '6px 14px', borderRadius: '8px', border: '1px solid transparent',
        background: 'var(--dsw-alias-label-primary, #1f1f1f)', color: 'var(--dsw-alias-bg-layer-1, #fff)',
        font: 'inherit', cursor: 'pointer',
      },
      danger: {
        padding: '4px 10px', borderRadius: '7px', border: '1px solid var(--dsw-alias-state-error-primary, #ef4444)',
        background: 'transparent', color: 'var(--dsw-alias-state-error-primary, #ef4444)', font: 'inherit', cursor: 'pointer',
        flexShrink: 0, whiteSpace: 'nowrap',
      },
      message: { margin: 0, fontSize: '12px', lineHeight: '18px', color: 'var(--dsw-alias-label-secondary, #8b8b8b)' },
      error: { margin: 0, fontSize: '12px', lineHeight: '18px', color: 'var(--dsw-alias-state-error-primary, #ef4444)' },
      dot: { display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', marginRight: '6px' },
      qr: { display: 'flex', justifyContent: 'center', padding: '8px', background: '#fff', borderRadius: '10px' },
      devices: { display: 'flex', flexDirection: 'column', gap: '8px' },
      device: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px' },
      deviceText: { display: 'flex', flexDirection: 'column', minWidth: 0, flex: 1 },
      deviceName: { fontSize: '13px', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
      enrollBox: {
        display: 'flex', flexDirection: 'column', gap: '8px', padding: '10px',
        border: '1px solid var(--dsw-alias-border-l2, #d0d0d0)', borderRadius: '8px',
        background: 'var(--dsw-alias-bg-layer-2, #f7f7f8)',
      },
      codeBlock: {
        display: 'block', margin: 0, padding: '8px', borderRadius: '6px',
        background: 'var(--dsw-alias-bg-layer-1, #fff)', border: '1px solid var(--dsw-alias-border-l3, #e5e5e5)',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '11px',
        lineHeight: '16px', whiteSpace: 'pre-wrap', wordBreak: 'break-all',
      },
      enrollCode: { fontSize: '20px', fontWeight: 700, letterSpacing: '0.18em', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
    }

    /**
     * @param {any} form
     * @returns {string}
     */
    function currentDomain(form) {
      const value = form?.state?.value
      return value !== null && typeof value === 'object' && typeof value.domain === 'string' ? value.domain : ''
    }

    /**
     * @param {number} ms
     * @returns {string}
     */
    function formatTime(ms) {
      if (!Number.isFinite(ms) || ms <= 0) return '—'
      try { return new Date(ms).toLocaleString('zh-CN', { hour12: false }) } catch { return '—' }
    }

    /**
     * @param {string} url
     * @param {object} [options]
     * @returns {Promise<any>}
     */
    async function api(url, options) {
      const response = await fetch(url, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'content-type': 'application/json' },
        ...options,
      })
      const body = await response.json().catch(() => ({}))
      if (!response.ok || body.ok === false) {
        throw new Error(body.error ?? `HTTP ${String(response.status)}`)
      }
      return body
    }

    /**
     * Settings page with a status card, pairing QR, devices, and the domain.
     * @param {any} props
     * @returns {any}
     */
    function TunnelSettings(props) {
      const { view, form } = props
      const current = currentDomain(form)
      const [draft, setDraft] = React.useState(current)
      const [saving, setSaving] = React.useState(false)
      const [message, setMessage] = React.useState('')
      const [status, setStatus] = React.useState(undefined)
      const [pairing, setPairing] = React.useState(undefined)
      const [busy, setBusy] = React.useState(false)
      const [pairingError, setPairingError] = React.useState('')
      const [relayDraft, setRelayDraft] = React.useState('')
      const [instanceDraft, setInstanceDraft] = React.useState('')
      const [tokenDraft, setTokenDraft] = React.useState('')
      const [savingConnection, setSavingConnection] = React.useState(false)
      const [connectionMessage, setConnectionMessage] = React.useState('')
      const [connectionInitialized, setConnectionInitialized] = React.useState(false)
      const [enrollCode, setEnrollCode] = React.useState('')
      const [enrollCommand, setEnrollCommand] = React.useState('')
      const [enrollMessage, setEnrollMessage] = React.useState('')
      const [enrollBusy, setEnrollBusy] = React.useState(false)
      const enrollTimer = React.useRef(undefined)

      React.useEffect(() => { setDraft(current) }, [current])

      React.useEffect(() => {
        if (view === 'summary') return undefined
        let cancelled = false
        const load = async () => {
          try {
            const next = await api('/remote-tunnel/status')
            if (!cancelled) setStatus(next)
          } catch (error) {
            if (!cancelled) setStatus({ connected: false, lastError: error instanceof Error ? error.message : String(error) })
          }
        }
        void load()
        const timer = setInterval(load, 5000)
        return () => { cancelled = true; clearInterval(timer) }
      }, [view])

      React.useEffect(() => () => {
        if (enrollTimer.current !== undefined) {
          clearInterval(enrollTimer.current)
          enrollTimer.current = undefined
        }
      }, [])

      React.useEffect(() => {
        if (connectionInitialized || status === undefined) return
        setRelayDraft(typeof status.relayUrl === 'string' ? status.relayUrl : '')
        setInstanceDraft(typeof status.instanceId === 'string' ? status.instanceId : '')
        setConnectionInitialized(true)
      }, [status, connectionInitialized])

      if (view === 'summary') {
        return React.createElement('span', null, current === '' ? '未配置实例域名' : current)
      }

      if (form === undefined || form.state === undefined) {
        return React.createElement('p', { style: styles.hint }, '配置不可用：请在 Plugins 里启用这个 bundle。')
      }
      if (form.state.status === 'loading') {
        return React.createElement('p', { style: styles.hint }, '读取配置…')
      }
      if (form.state.status === 'unavailable') {
        return React.createElement('p', { style: styles.hint }, 'Host 没有暴露这项配置。')
      }

      const save = async () => {
        const next = draft.trim()
        if (!DOMAIN_PATTERN.test(next)) {
          setMessage('请输入域名，例如 mac.relay.example.com；不要带 https:// 或路径。')
          return
        }
        setSaving(true)
        setMessage('')
        try {
          const accepted = await form.mutate(
            [{ op: 'set', path: ['domain'], value: next }],
            form.state.revision,
          )
          setMessage(accepted ? '已保存。重启 dsh web 后对 trustedHosts/publicUrl 生效。' : '保存被拒绝，请重试。')
        } catch (error) {
          setMessage(error instanceof Error ? error.message : String(error))
        } finally {
          setSaving(false)
        }
      }

      const stopEnrollPolling = () => {
        if (enrollTimer.current !== undefined) {
          clearInterval(enrollTimer.current)
          enrollTimer.current = undefined
        }
      }

      const pollEnrollment = async (code) => {
        try {
          const result = await api(`/remote-tunnel/enroll/poll?code=${encodeURIComponent(code)}`)
          if (result.pending === true) return
          if (result.imported === true) {
            stopEnrollPolling()
            setEnrollCode('')
            setEnrollCommand('')
            setEnrollMessage('已从服务器导入实例令牌并连接。')
            setTokenDraft('')
            const next = await api('/remote-tunnel/status')
            setStatus(next)
          }
        } catch (error) {
          stopEnrollPolling()
          setEnrollMessage(error instanceof Error ? error.message : String(error))
        }
      }

      const startEnrollment = async () => {
        stopEnrollPolling()
        setEnrollBusy(true)
        setEnrollMessage('')
        try {
          const result = await api('/remote-tunnel/enroll/start', { method: 'POST', body: '{}' })
          setEnrollCode(String(result.code))
          setEnrollCommand(String(result.command))
          setEnrollMessage('等待服务器执行命令…')
          void pollEnrollment(String(result.code))
          enrollTimer.current = setInterval(() => { void pollEnrollment(String(result.code)) }, 2000)
        } catch (error) {
          setEnrollMessage(error instanceof Error ? error.message : String(error))
        } finally {
          setEnrollBusy(false)
        }
      }

      const copyEnrollCommand = async () => {
        try {
          await navigator.clipboard.writeText(enrollCommand)
          setEnrollMessage('命令已复制，粘贴到 OrcaTerm 执行即可。')
        } catch {
          setEnrollMessage('复制失败，请手动选择命令文本。')
        }
      }

      const saveConnection = async () => {
        stopEnrollPolling()
        setEnrollCode('')
        setEnrollCommand('')
        setSavingConnection(true)
        setConnectionMessage('')
        try {
          const body = {
            relayUrl: relayDraft.trim(),
            instanceId: instanceDraft.trim(),
          }
          if (tokenDraft.trim() !== '') body.instanceToken = tokenDraft.trim()
          const result = await api('/remote-tunnel/config', {
            method: 'POST',
            body: JSON.stringify(body),
          })
          setTokenDraft('')
          if (result.restartRequired === true) {
            setConnectionMessage('已保存。域名已变化，重启 dsh web 后生效。')
          } else if (result.status?.connected === true) {
            setConnectionMessage('已保存，relay 已连接。')
          } else {
            setConnectionMessage('已保存，正在连接 relay…')
          }
          const next = await api('/remote-tunnel/status')
          setStatus(next)
        } catch (error) {
          setConnectionMessage(error instanceof Error ? error.message : String(error))
        } finally {
          setSavingConnection(false)
        }
      }

      const createPair = async () => {
        setBusy(true)
        setPairingError('')
        try {
          const next = await api('/remote-tunnel/pair', { method: 'POST', body: '{}' })
          setPairing(next)
        } catch (error) {
          setPairingError(error instanceof Error ? error.message : String(error))
        } finally {
          setBusy(false)
        }
      }

      const revoke = async (deviceId) => {
        setBusy(true)
        setPairingError('')
        try {
          await api('/remote-tunnel/revoke', { method: 'POST', body: JSON.stringify({ deviceId }) })
          const next = await api('/remote-tunnel/status')
          setStatus(next)
        } catch (error) {
          setPairingError(error instanceof Error ? error.message : String(error))
        } finally {
          setBusy(false)
        }
      }

      const connected = status?.connected === true
      const devices = Array.isArray(status?.devices) ? status.devices : []
      const isSsh = status?.transport === 'ssh' || status?.mode === 'ssh'
      const configError = typeof status?.configError === 'string' ? status.configError : ''
      const lastError = typeof status?.lastError === 'string' ? status.lastError : ''
      const mode = isSsh ? 'SSH fallback' : 'HTTPS relay'
      const expiresIn = pairing === undefined || !Number.isFinite(pairing.expiresAt)
        ? ''
        : Math.max(0, Math.ceil((pairing.expiresAt - Date.now()) / 1000))

      return React.createElement('div', { style: styles.page },
        React.createElement('div', { style: styles.card },
          React.createElement('div', { style: styles.row },
            React.createElement('strong', { style: styles.label }, '连接状态'),
            React.createElement('span', { style: styles.hint },
              React.createElement('span', {
                style: { ...styles.dot, background: connected ? '#22c55e' : '#f59e0b' },
              }),
              connected ? '在线' : '离线')),
          React.createElement('p', { style: styles.hint },
            `${mode} · ${status?.publicHost ?? current ?? '未配置'}`),
          React.createElement('p', { style: styles.hint },
            React.createElement('span', { style: styles.mono }, status?.relayUrl ?? '未配置 relayUrl')),
          configError === ''
            ? null
            : React.createElement('p', { style: styles.error }, configError),
          lastError === '' || lastError === configError
            ? null
            : React.createElement('p', { style: styles.error }, lastError)),
        React.createElement('div', { style: styles.card },
          React.createElement('strong', { style: styles.label }, '服务器连接'),
          isSsh
            ? React.createElement('p', { style: styles.hint },
              '当前是 SSH fallback 模式。把 profile 的 mode 改为 relay 并重启后，可在这里填写 relay 配置。')
            : React.createElement(React.Fragment, null,
              React.createElement('p', { style: styles.hint },
                '填写 relay 地址、实例 ID 和实例令牌。令牌只写入本机 0600 文件，不会进入 cordis.patch.yml。'),
              React.createElement('label', { style: styles.label, htmlFor: 'remote-tunnel-relay-url' }, 'Relay URL'),
              React.createElement('input', {
                id: 'remote-tunnel-relay-url',
                style: styles.input,
                value: relayDraft,
                placeholder: 'wss://relay.example.com/tunnel',
                spellCheck: false,
                autoComplete: 'off',
                disabled: savingConnection,
                onChange: event => setRelayDraft(event.target.value),
              }),
              React.createElement('label', { style: styles.label, htmlFor: 'remote-tunnel-instance-id' }, '实例 ID'),
              React.createElement('input', {
                id: 'remote-tunnel-instance-id',
                style: styles.input,
                value: instanceDraft,
                placeholder: 'mac',
                spellCheck: false,
                autoComplete: 'off',
                disabled: savingConnection,
                onChange: event => setInstanceDraft(event.target.value),
              }),
              React.createElement('label', { style: styles.label, htmlFor: 'remote-tunnel-token' }, '实例令牌'),
              React.createElement('input', {
                id: 'remote-tunnel-token',
                style: styles.input,
                type: 'password',
                value: tokenDraft,
                placeholder: status?.tokenConfigured === true ? '已配置，留空保持不变' : '粘贴 server/install.sh 打印的令牌',
                spellCheck: false,
                autoComplete: 'new-password',
                disabled: savingConnection,
                onChange: event => setTokenDraft(event.target.value),
              }),
              React.createElement('div', { style: styles.actions },
                React.createElement('button', {
                  type: 'button',
                  style: styles.primary,
                  disabled: savingConnection,
                  onClick: () => { void saveConnection() },
                }, savingConnection ? '保存中…' : '保存并连接'),
                React.createElement('button', {
                  type: 'button',
                  style: styles.button,
                  disabled: savingConnection || enrollBusy,
                  onClick: () => { void startEnrollment() },
                }, enrollBusy ? '生成导入码…' : '从服务器自动导入')),
              connectionMessage === ''
                ? null
                : React.createElement('p', {
                  style: connectionMessage.startsWith('已保存') ? styles.message : styles.error,
                }, connectionMessage),
              enrollCode === ''
                ? null
                : React.createElement('div', { style: styles.enrollBox },
                  React.createElement('span', { style: styles.hint }, '一次性导入码（5 分钟有效）'),
                  React.createElement('span', { style: styles.enrollCode }, enrollCode),
                  React.createElement('span', { style: styles.hint }, '在 OrcaTerm 执行下面命令；插件会自动领取令牌并连接：'),
                  React.createElement('code', { style: styles.codeBlock }, enrollCommand),
                  React.createElement('div', { style: styles.actions },
                    React.createElement('button', {
                      type: 'button',
                      style: styles.button,
                      onClick: () => { void copyEnrollCommand() },
                    }, '复制命令'),
                    React.createElement('button', {
                      type: 'button',
                      style: styles.button,
                      onClick: () => {
                        stopEnrollPolling()
                        setEnrollCode('')
                        setEnrollCommand('')
                        setEnrollMessage('已取消。')
                      },
                    }, '取消'))),
              enrollMessage === ''
                ? null
                : React.createElement('p', {
                  style: enrollMessage.includes('已') ? styles.message : styles.hint,
                }, enrollMessage))),
        React.createElement('div', { style: styles.card },
          React.createElement('strong', { style: styles.label }, '手机配对'),
          React.createElement('p', { style: styles.hint },
            isSsh
              ? '当前是 SSH fallback 模式；扫码配对需要部署 HTTPS relay 并把 mode 改为 relay。'
              : '点击生成一次性二维码，用 Android App 扫描。配对码 2 分钟有效、只能使用一次。'),
          isSsh ? null : React.createElement('div', { style: styles.actions },
            React.createElement('button', {
              type: 'button',
              style: styles.primary,
              disabled: busy || !connected,
              onClick: () => { void createPair() },
            }, busy ? '处理中…' : '生成配对二维码')),
          isSsh || pairingError === ''
            ? null
            : React.createElement('p', { style: styles.error }, pairingError),
          isSsh || pairing === undefined
            ? null
            : React.createElement('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px', alignItems: 'center' } },
              React.createElement('div', {
                style: styles.qr,
                dangerouslySetInnerHTML: { __html: pairing.svg },
              }),
              React.createElement('p', { style: styles.hint },
                `${expiresIn > 0 ? `${String(expiresIn)} 秒内有效` : '已过期，请重新生成'} · 仅显示这一次`)),
          isSsh
            ? null
            : devices.length === 0
              ? React.createElement('p', { style: styles.hint }, '还没有已配对设备。')
              : React.createElement('div', { style: styles.devices },
              devices.map(device => React.createElement('div', { key: device.id, style: styles.device },
                React.createElement('div', { style: styles.deviceText },
                  React.createElement('span', { style: styles.deviceName }, device.name ?? '设备'),
                  React.createElement('span', { style: styles.hint },
                    `配对 ${formatTime(device.createdAt)} · 最近 ${formatTime(device.lastSeenAt)}`)),
                React.createElement('button', {
                  type: 'button',
                  style: styles.danger,
                  disabled: busy,
                  onClick: () => { void revoke(device.id) },
                }, '撤销')))),
          isSsh
            ? null
            : React.createElement('div', { style: styles.actions },
              React.createElement('button', {
                type: 'button',
                style: styles.button,
                disabled: busy,
                onClick: async () => {
                  try {
                    const next = await api('/remote-tunnel/status')
                    setStatus(next)
                    setPairingError('')
                  } catch (error) {
                    setPairingError(error instanceof Error ? error.message : String(error))
                  }
                },
              }, '刷新设备列表'))),
        React.createElement('div', { style: styles.card },
          React.createElement('label', { style: styles.label, htmlFor: 'dsh-remote-tunnel-domain' }, '公网实例域名'),
          React.createElement('input', {
            id: 'dsh-remote-tunnel-domain',
            style: styles.input,
            value: draft,
            placeholder: 'mac.relay.example.com',
            spellCheck: false,
            autoComplete: 'off',
            disabled: !form.state.writable || saving,
            onChange: event => setDraft(event.target.value),
          }),
          React.createElement('p', { style: styles.hint },
            '只填域名，必须与 relay 分配的实例域名一致。修改后需要重启 dsh web，域名才会进入 trustedHosts 和 publicUrl。'),
          React.createElement('div', { style: styles.actions },
            React.createElement('button', {
              type: 'button',
              style: styles.button,
              disabled: !form.state.writable || saving || draft.trim() === current,
              onClick: () => { void save() },
            }, saving ? '保存中…' : '保存')),
          message === ''
            ? null
            : React.createElement('p', {
              style: message.startsWith('已保存') ? styles.message : styles.error,
            }, message)),
        React.createElement('p', { style: styles.hint },
          '实例令牌存放在 ',
          React.createElement('span', { style: styles.mono }, status?.tokenFile ?? '~/.dsh/dsh-remote-tunnel/instance-token'),
          '（0600），不要写进 cordis.patch.yml 或提交到仓库。'))
    }

    /** Required service: the Client slot registry. */
    const inject = ['slots']

    /**
     * Register the row page on the Plugins page.
     * @param {any} ctx
     */
    function apply(ctx) {
      ctx.slots.inject('plugins.row.config', () => ctx.slots.register({
        name: 'plugins.row.config',
        key: CONFIG_KEY,
      }, TunnelSettings))
    }

    exports.apply = apply
    exports.inject = inject
    return module.exports
  },
})
