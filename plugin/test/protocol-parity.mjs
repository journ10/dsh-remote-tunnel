import './isolated-home.mjs'

/**
 * The relay and plugin each ship their own copy of the wire protocol so both
 * packages stay standalone. This test fails when the copies drift.
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { FRAME, decodeFrame, encodeControl, encodeFrame } from '../lib/protocol.js'

const here = dirname(fileURLToPath(import.meta.url))
const pluginCopy = readFileSync(join(here, '..', 'lib', 'protocol.js'), 'utf8')
const serverCopy = readFileSync(join(here, '..', '..', 'server', 'src', 'protocol.js'), 'utf8')
assert.equal(pluginCopy, serverCopy, 'plugin/lib/protocol.js and server/src/protocol.js have drifted')

const frame = encodeFrame(FRAME.DATA, 42, Buffer.from('hello'))
const decoded = decodeFrame(frame)
assert.equal(decoded.type, FRAME.DATA)
assert.equal(decoded.streamId, 42)
assert.equal(decoded.payload.toString(), 'hello')

const control = decodeFrame(encodeControl({ type: 'hello', protocol: 1 }))
assert.equal(control.streamId, 0)
assert.deepEqual(JSON.parse(control.payload.toString()), { type: 'hello', protocol: 1 })

console.log('protocol parity ok')
