/** Minimal leveled logger with secret redaction. */

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 }
const SECRET_KEY = /token|secret|password|authorization|cookie|code/iu

/**
 * Redact secret-shaped object fields.
 * @param {unknown} value
 * @param {number} [depth]
 * @returns {unknown}
 */
export function redact(value, depth = 0) {
  if (depth > 4) return '[depth]'
  if (value === null || typeof value !== 'object') return value
  if (Array.isArray(value)) return value.map(item => redact(item, depth + 1))
  const out = {}
  for (const [key, item] of Object.entries(value)) {
    out[key] = SECRET_KEY.test(key) ? '[redacted]' : redact(item, depth + 1)
  }
  return out
}

/**
 * @param {string} level
 * @param {string} message
 * @param {unknown} [fields]
 * @param {Console} [sink]
 */
function write(level, message, fields, sink) {
  const line = `[relay] ${level} ${message}`
  if (fields === undefined) sink[level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'log'](line)
  else sink[level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'log'](line, redact(fields))
}

/**
 * Create the relay logger.
 * @param {string} level
 * @param {Console} [sink]
 */
export function createLogger(level = 'info', sink = console) {
  const threshold = LEVELS[level] ?? LEVELS.info
  return {
    level,
    debug: (message, fields) => { if (threshold <= LEVELS.debug) write('debug', message, fields, sink) },
    info: (message, fields) => { if (threshold <= LEVELS.info) write('info', message, fields, sink) },
    warn: (message, fields) => { if (threshold <= LEVELS.warn) write('warn', message, fields, sink) },
    error: (message, fields) => { if (threshold <= LEVELS.error) write('error', message, fields, sink) },
  }
}
