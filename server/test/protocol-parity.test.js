import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { FRAME, decodeFrame, encodeControl, encodeFrame } from '../src/protocol.js'

const here = dirname(fileURLToPath(import.meta.url))

test('the relay and plugin protocol copies are identical', () => {
  const server = readFileSync(join(here, '..', 'src', 'protocol.js'), 'utf8')
  const plugin = readFileSync(join(here, '..', '..', 'plugin', 'lib', 'protocol.js'), 'utf8')
  assert.equal(server, plugin)
})

test('frames round-trip', () => {
  const decoded = decodeFrame(encodeFrame(FRAME.DATA, 7, Buffer.from('x')))
  assert.equal(decoded.type, FRAME.DATA)
  assert.equal(decoded.streamId, 7)
  assert.equal(decoded.payload.toString(), 'x')
  const control = decodeFrame(encodeControl({ type: 'hello', protocol: 1 }))
  assert.equal(control.streamId, 0)
  assert.equal(JSON.parse(control.payload.toString()).type, 'hello')
  assert.throws(() => decodeFrame(Buffer.alloc(4)), /shorter/u)
})
