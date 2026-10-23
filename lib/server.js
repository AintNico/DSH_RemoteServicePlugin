import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'web')
const MAX_BODY_BYTES = 64 * 1024
const PAIR_LOCK_MS = 60 * 1000
const PAIR_MAX_FAILURES = 5

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  })
  res.end(body)
}

function sendFile(res, file, contentType) {
  try {
    const body = readFileSync(join(WEB_DIR, file))
    res.writeHead(200, {
      'content-type': contentType,
      'content-length': body.length,
      'cache-control': 'no-store',
    })
    res.end(body)
  } catch {
    sendJson(res, 500, { error: 'asset-missing', asset: file })
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body-too-large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function isLoopback(req) {
  const address = req.socket.remoteAddress || ''
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

function bearer(req) {
  const header = req.headers.authorization || ''
  const match = /^Bearer\s+(.+)$/i.exec(header)
  if (match) return match[1].trim()
  const legacy = req.headers['x-dsh-token']
  return typeof legacy === 'string' ? legacy.trim() : ''
}

export function createRemoteServer({ state, options, listSessions }) {
  const failures = new Map()

  function lockedOut(ip) {
    const entry = failures.get(ip)
    if (!entry) return false
    if (entry.until && entry.until > Date.now()) return true
    if (entry.until && entry.until <= Date.now()) failures.delete(ip)
    return false
  }

  function recordFailure(ip) {
    const entry = failures.get(ip) || { count: 0, until: 0 }
    entry.count += 1
    if (entry.count >= PAIR_MAX_FAILURES) {
      entry.count = 0
      entry.until = Date.now() + PAIR_LOCK_MS
    }
    failures.set(ip, entry)
  }

  async function route(req, res) {
    const url = new URL(req.url || '/', 'http://localhost')
    const pathname = url.pathname

    if (req.method === 'GET' && pathname === '/api/health') {
      return sendJson(res, 200, {
        ok: true,
        version: options.version,
        pairing: Boolean(state.pairingSnapshot()),
        devices: state.listDevices().length,
      })
    }

    if (req.method === 'GET' && pathname === '/api/info') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, options.info?.() ?? {})
    }

    if (req.method === 'GET' && pathname === '/api/pairing') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, {
        pairing: state.pairingSnapshot(),
        devices: state.listDevices(),
      })
    }

    if (req.method === 'POST' && pathname === '/api/pairing/regenerate') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      const pairing = state.beginPairing(options.pairingTtlMs)
      return sendJson(res, 200, { pairing })
    }

    if (req.method === 'GET' && pathname === '/api/devices') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, { devices: state.listDevices() })
    }

    if (req.method === 'POST' && pathname === '/api/devices/revoke') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      const body = JSON.parse((await readBody(req)) || '{}')
      const removed = state.revokeDevice(String(body.id || ''))
      return sendJson(res, removed ? 200 : 404, { revoked: removed })
    }

    if (req.method === 'POST' && pathname === '/api/pair') {
      const ip = req.socket.remoteAddress || 'unknown'
      if (lockedOut(ip)) {
        return sendJson(res, 429, { error: 'too-many-attempts', retryAfterMs: PAIR_LOCK_MS })
      }
      const body = JSON.parse((await readBody(req)) || '{}')
      const result = state.consumePairingCode(body.code, body.deviceName)
      if (!result.ok) {
        recordFailure(ip)
        return sendJson(res, 401, { error: result.reason })
      }
      state.audit({ kind: 'device/paired', detail: { device: result.device.name } })
      return sendJson(res, 200, { token: result.token, device: result.device })
    }

    if (req.method === 'GET' && pathname === '/api/sessions') {
      const device = state.deviceForToken(bearer(req))
      if (!device) return sendJson(res, 401, { error: 'unauthorized' })
      try {
        const sessions = await listSessions()
        return sendJson(res, 200, { sessions })
      } catch (error) {
        return sendJson(res, 503, { error: 'sessions-unavailable', message: String(error?.message || error) })
      }
    }

    if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      return sendFile(res, 'index.html', 'text/html; charset=utf-8')
    }

    if (req.method === 'GET' && pathname === '/desktop') {
      if (!isLoopback(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendFile(res, 'desktop.html', 'text/html; charset=utf-8')
    }

    if (req.method === 'GET' && pathname === '/icon.svg') {
      return sendFile(res, join('..', 'icon.svg'), 'image/svg+xml')
    }

    return sendJson(res, 404, { error: 'not-found' })
  }

  const server = createServer((req, res) => {
    route(req, res).catch((error) => {
      const status = error?.message === 'body-too-large' ? 413 : 500
      console.error('[remote-service] request failed:', error)
      if (!res.headersSent) sendJson(res, status, { error: 'internal', message: String(error?.message || error) })
      else res.end()
    })
  })

  server.on('clientError', (error, socket) => {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n')
  })

  return {
    listen() {
      return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(options.port, options.host, () => {
          server.removeListener('error', reject)
          server.on('error', (error) => console.error('[remote-service] server error:', error))
          resolve(server.address())
        })
      })
    },
    close() {
      return new Promise((resolve) => server.close(() => resolve()))
    },
  }
}
