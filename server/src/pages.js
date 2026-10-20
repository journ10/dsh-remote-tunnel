/** Small self-contained HTML pages served by the relay. */

/**
 * @param {string} title
 * @param {string} body
 * @returns {string}
 */
function page(title, body) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="referrer" content="no-referrer">
<title>${title} · DSH Remote</title>
<style>
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body {
  margin: 0; min-height: 100dvh; display: grid; place-items: center; padding: 24px;
  font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  background: #0b0d10; color: #f2f4f7;
}
main { width: min(420px, 100%); }
h1 { margin: 0 0 10px; font-size: 22px; letter-spacing: -0.02em; }
p { margin: 0 0 12px; color: #aeb6c2; }
code { font-size: 13px; background: #171b21; border: 1px solid #262c35; border-radius: 6px; padding: 1px 5px; }
.card { background: #11151a; border: 1px solid #242b34; border-radius: 16px; padding: 22px; }
.hint { font-size: 13px; color: #8b95a5; }
@media (prefers-color-scheme: light) {
  body { background: #f6f7f9; color: #17191c; }
  p { color: #5c6470; }
  code { background: #eef1f4; border-color: #dce1e7; }
  .card { background: #fff; border-color: #e4e8ed; }
  .hint { color: #737c89; }
}
</style>
</head>
<body><main><div class="card">${body}</div></main></body>
</html>`
}

/**
 * @param {string} message
 * @returns {string}
 */
export function pairErrorPage(message) {
  return page('配对失败', `<h1>配对失败</h1>
<p>${message}</p>
<p class="hint">在 DSH 的 <strong>Plugins → dsh-remote-tunnel</strong> 页面重新生成二维码，再扫描一次。</p>`)
}

/** @returns {string} */
export function unauthorizedPage() {
  return page('需要配对', `<h1>需要配对</h1>
<p>这个设备还没有连接到 DSH。</p>
<p class="hint">打开 DSH 的 <strong>Plugins → dsh-remote-tunnel</strong> 页面，用手机扫描二维码完成配对。</p>`)
}

/** @returns {string} */
export function notFoundPage() {
  return page('未找到', '<h1>未找到</h1><p>这个地址没有对应的 DSH 实例。</p>')
}

/**
 * @param {string} instanceHost
 * @returns {string}
 */
export function offlinePage(instanceHost) {
  return page('实例离线', `<h1>DSH 暂时离线</h1>
<p><code>${instanceHost}</code> 的中继连接不在线。</p>
<p class="hint">确认 DSH 正在运行、插件已启用，并且这台电脑可以访问互联网。插件会自动重连。</p>`)
}

/**
 * @param {{publicHost: string, instanceBaseDomain: string}} options
 * @returns {string}
 */
export function landingPage({ publicHost, instanceBaseDomain }) {
  return page('DSH Remote', `<h1>DSH Remote</h1>
<p>这是一个自托管的 DSH HTTPS 中继。</p>
<p class="hint">中继地址：<code>${publicHost}</code><br>
实例域名：<code>*.${instanceBaseDomain}</code></p>
<p class="hint">在 DSH 插件里生成配对二维码，然后用手机扫描。</p>`)
}

/**
 * @param {string} message
 * @param {number} [status]
 * @returns {string}
 */
export function errorPage(message, status = 400) {
  return page(`错误 ${String(status)}`, `<h1>请求失败</h1><p>${message}</p>`)
}
