import { createRequire } from 'node:module'
import { deflateSync } from 'node:zlib'

const require = createRequire(import.meta.url)
// Vendored (MIT) QR encoder: keeps the plugin installable with zero npm dependencies.
const qrcode = require('./vendor/qrcode.js')

/** Deep link the phone understands; keep it stable, the Android side parses it. */
export function pairingPayload(url, code) {
  return `dshremote://pair?v=1&url=${encodeURIComponent(url)}&code=${encodeURIComponent(code)}`
}

/**
 * Renders text as a PNG. Grayscale (colour type 0) keeps the encoder tiny: one filter byte
 * per row plus one byte per pixel.
 */
export function renderQrPng(text, { scale = 8, margin = 4, errorCorrection = 'M' } = {}) {
  const qr = qrcode(0, errorCorrection) // typeNumber 0 = pick the smallest that fits
  qr.addData(text)
  qr.make()

  const modules = qr.getModuleCount()
  const size = (modules + margin * 2) * scale
  const raw = Buffer.alloc((size + 1) * size, 0xff)

  for (let y = 0; y < size; y++) {
    const rowStart = y * (size + 1)
    raw[rowStart] = 0 // filter type: none
    for (let x = 0; x < size; x++) {
      const moduleX = Math.floor(x / scale) - margin
      const moduleY = Math.floor(y / scale) - margin
      const dark = moduleX >= 0 && moduleY >= 0 && moduleX < modules && moduleY < modules
        && qr.isDark(moduleY, moduleX)
      raw[rowStart + 1 + x] = dark ? 0x00 : 0xff
    }
  }

  return PNG_HEADER(size, deflateSync(raw, { level: 9 }))
}

function PNG_HEADER(size, idat) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 0 // colour type: grayscale
  ihdr[10] = 0 // compression
  ihdr[11] = 0 // filter
  ihdr[12] = 0 // interlace

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length, 0)
  const typeAndData = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(typeAndData) >>> 0, 0)
  return Buffer.concat([length, typeAndData, crc])
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    }
    table[n] = c
  }
  return table
})()

function crc32(buffer) {
  let crc = -1
  for (let i = 0; i < buffer.length; i++) {
    crc = CRC_TABLE[(crc ^ buffer[i]) & 0xff] ^ (crc >>> 8)
  }
  return crc ^ -1
}
