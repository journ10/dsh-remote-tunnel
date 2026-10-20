/**
 * Wire framing shared by the relay and the DSH plugin.
 *
 * This file is duplicated at `plugin/lib/protocol.js` so each package stays
 * installable on its own. `test/protocol-parity.test.js` fails if the copies
 * drift. See docs/protocol.md for the contract.
 */

/** Protocol version carried by the `hello` control message. */
export const PROTOCOL_VERSION = 1

/** Largest DATA payload either side may put in one frame. */
export const MAX_FRAME_BYTES = 64 * 1024

/** Per-direction, per-stream receive window. */
export const INITIAL_WINDOW = 1024 * 1024

/** Frame type values. Keep in sync with docs/protocol.md. */
export const FRAME = Object.freeze({
  OPEN: 1,
  HEAD: 2,
  DATA: 3,
  END: 4,
  RESET: 5,
  WINDOW: 6,
  CLOSE: 7,
  CONTROL: 8,
  PING: 9,
  PONG: 10,
})

const KNOWN_TYPES = new Set(Object.values(FRAME))

/**
 * @typedef {object} Frame
 * @property {number} type
 * @property {number} streamId
 * @property {Buffer} payload
 */

/**
 * Encode one binary WebSocket frame.
 * @param {number} type - a {@link FRAME} value.
 * @param {number} streamId - 0 for control frames.
 * @param {Buffer|Uint8Array|string|object} [payload] - bytes, or a value to JSON-encode.
 * @returns {Buffer} the frame.
 */
export function encodeFrame(type, streamId, payload) {
  if (!KNOWN_TYPES.has(type)) throw new TypeError(`unknown frame type: ${String(type)}`)
  if (!Number.isInteger(streamId) || streamId < 0 || streamId > 0xffffffff) {
    throw new TypeError(`invalid stream id: ${String(streamId)}`)
  }
  let body
  if (payload === undefined) body = Buffer.alloc(0)
  else if (Buffer.isBuffer(payload)) body = payload
  else if (payload instanceof Uint8Array) body = Buffer.from(payload)
  else if (typeof payload === 'string') body = Buffer.from(payload, 'utf8')
  else body = Buffer.from(JSON.stringify(payload), 'utf8')
  const frame = Buffer.allocUnsafe(5 + body.length)
  frame[0] = type
  frame.writeUInt32BE(streamId, 1)
  body.copy(frame, 5)
  return frame
}

/**
 * Decode a WebSocket binary message.
 * @param {Buffer|Uint8Array|ArrayBuffer} data
 * @returns {Frame}
 */
export function decodeFrame(data) {
  const buffer = Buffer.isBuffer(data)
    ? data
    : data instanceof ArrayBuffer ? Buffer.from(data) : Buffer.from(data.buffer, data.byteOffset, data.byteLength)
  if (buffer.length < 5) throw new RangeError('frame shorter than 5 bytes')
  const type = buffer[0]
  if (!KNOWN_TYPES.has(type)) throw new RangeError(`unknown frame type: ${String(type)}`)
  return {
    type,
    streamId: buffer.readUInt32BE(1),
    payload: buffer.subarray(5),
  }
}

/**
 * Parse a JSON payload from a frame.
 * @param {Frame} frame
 * @returns {any}
 */
export function decodeJson(frame) {
  if (frame.payload.length === 0) return {}
  return JSON.parse(frame.payload.toString('utf8'))
}

/**
 * Encode a control message (stream id 0).
 * @param {object} message
 * @returns {Buffer}
 */
export function encodeControl(message) {
  return encodeFrame(FRAME.CONTROL, 0, message)
}

/**
 * Encode a JSON frame for one stream.
 * @param {number} type
 * @param {number} streamId
 * @param {object} value
 * @returns {Buffer}
 */
export function encodeJsonFrame(type, streamId, value) {
  return encodeFrame(type, streamId, value)
}

/**
 * Split a byte buffer into DATA-sized chunks.
 * @param {Buffer} buffer
 * @returns {Buffer[]}
 */
export function chunkBuffer(buffer) {
  const chunks = []
  for (let offset = 0; offset < buffer.length; offset += MAX_FRAME_BYTES) {
    chunks.push(buffer.subarray(offset, Math.min(offset + MAX_FRAME_BYTES, buffer.length)))
  }
  return chunks
}
