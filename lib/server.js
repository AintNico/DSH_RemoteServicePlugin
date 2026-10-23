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

async function* limitedBody(req, maxBytes) {
  let total = 0
  for await (const chunk of req) {
    total += chunk.length
    if (total > maxBytes) throw new Error('upload exceeds the size limit')
    yield chunk
  }
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

export function createRemoteServer({ state, options, adapter }) {
  const failures = new Map()
  const subscribers = new Set()

  /**
   * Loopback trust must cover only genuinely local peers. A tunnel (Cloudflare, SSH) also
   * arrives from 127.0.0.1 but carries a forwarding header and a public Host, so it still
   * has to present a device token.
   */
  function isLocalPeer(req) {
    if (!isLoopback(req)) return false
    if (req.headers['x-forwarded-for'] || req.headers['x-real-ip'] || req.headers['cf-connecting-ip']) {
      return false
    }
    const host = String(req.headers.host || '')
    return host.startsWith('127.0.0.1') || host.startsWith('localhost') || host.startsWith('[::1]')
  }

  function authorized(req) {
    return isLocalPeer(req) || Boolean(state.deviceForToken(bearer(req)))
  }

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
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, { ...(options.info?.() ?? {}), eventStreams: subscribers.size })
    }

    if (req.method === 'GET' && pathname === '/api/dev/approval-hooks') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, {
        hooks: options.describeApprovalHooks?.() ?? [],
        recent: options.recentApprovals?.() ?? [],
      })
    }

    if (req.method === 'POST' && pathname === '/api/dev/approval-probe') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      if (!options.probeApproval) return sendJson(res, 501, { error: 'probe-unavailable' })
      try {
        return sendJson(res, 200, await options.probeApproval())
      } catch (error) {
        return sendJson(res, 500, { error: 'probe-failed', message: String(error?.message || error) })
      }
    }

    // Local-only fixture used to exercise the approval card UI without a real gated tool.
    if (req.method === 'POST' && pathname === '/api/dev/approval-demo') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      const body = JSON.parse((await readBody(req)) || '{}')
      options.startApprovalDemo?.(String(body.sessionId ?? ''))
      return sendJson(res, 200, { started: true, approvals: options.listApprovals?.() ?? [] })
    }

    // Local-only fixture that emits a synthetic token stream for the streaming UI.
    if (req.method === 'POST' && pathname === '/api/dev/stream-demo') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      const body = JSON.parse((await readBody(req)) || '{}')
      options.startStreamDemo?.(String(body.sessionId ?? ''))
      return sendJson(res, 200, { started: true })
    }

    if (req.method === 'GET' && pathname === '/api/pairing') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, {
        pairing: state.pairingSnapshot(),
        devices: state.listDevices(),
      })
    }

    if (req.method === 'POST' && pathname === '/api/pairing/regenerate') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      const pairing = state.beginPairing(options.pairingTtlMs)
      return sendJson(res, 200, { pairing })
    }

    if (req.method === 'GET' && pathname === '/api/devices') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendJson(res, 200, { devices: state.listDevices() })
    }

    if (req.method === 'POST' && pathname === '/api/devices/revoke') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
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

    if (req.method === 'GET' && pathname === '/api/workspaces') {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      try {
        return sendJson(res, 200, await adapter.listWorkspaces())
      } catch (error) {
        return sendJson(res, 503, { error: 'workspaces-unavailable', message: String(error?.message || error) })
      }
    }
    if (req.method === 'GET' && pathname === '/api/sessions') {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      try {
        return sendJson(res, 200, { sessions: await adapter.listSessions() })
      } catch (error) {
        return sendJson(res, 503, { error: 'sessions-unavailable', message: String(error?.message || error) })
      }
    }

    const messages = /^\/api\/sessions\/([^/]+)\/messages$/.exec(pathname)
    if (req.method === 'GET' && messages) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const since = Number(url.searchParams.get('since') || 0) || 0
      try {
        return sendJson(res, 200, await adapter.readMessages(decodeURIComponent(messages[1]), since))
      } catch (error) {
        return sendJson(res, 503, { error: 'messages-unavailable', message: String(error?.message || error) })
      }
    }

    const prompt = /^\/api\/sessions\/([^/]+)\/prompt$/.exec(pathname)
    if (req.method === 'POST' && prompt) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const body = JSON.parse((await readBody(req)) || '{}')
      const text = String(body.text ?? '').trim()
      if (!text) return sendJson(res, 400, { error: 'empty-text' })
      if (text.length > 32000) return sendJson(res, 413, { error: 'text-too-long' })
      try {
        const requestId = await adapter.sendMessage(decodeURIComponent(prompt[1]), text)
        state.audit({ kind: 'prompt', detail: { session: decodeURIComponent(prompt[1]), chars: text.length } })
        return sendJson(res, 200, { accepted: true, requestId })
      } catch (error) {
        return sendJson(res, 503, { error: 'prompt-failed', message: String(error?.message || error) })
      }
    }

    const cancel = /^\/api\/sessions\/([^/]+)\/cancel$/.exec(pathname)
    if (req.method === 'POST' && cancel) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      try {
        adapter.cancelTurn(decodeURIComponent(cancel[1]))
        return sendJson(res, 200, { accepted: true })
      } catch (error) {
        return sendJson(res, 503, { error: 'cancel-failed', message: String(error?.message || error) })
      }
    }

    if (req.method === 'GET' && pathname === '/api/events') {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      return openEventStream(req, res)
    }

    const upload = /^\/api\/sessions\/([^/]+)\/upload$/.exec(pathname)
    if (req.method === 'POST' && upload) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const sessionId = decodeURIComponent(upload[1])
      const name = String(url.searchParams.get('name') || 'upload.bin').slice(0, 200)
      const caption = String(url.searchParams.get('text') || '').slice(0, 4000)
      const wantsPrompt = url.searchParams.get('prompt') !== 'false'
      try {
        const staged = await adapter.uploadFile(sessionId, limitedBody(req, options.maxUploadBytes), name)
        const requestId = wantsPrompt
          ? await adapter.sendFileMessage(sessionId, staged.receiptId, caption)
          : null
        state.audit({ kind: 'upload', detail: { sessionId, name, bytes: staged.file?.bytes ?? null } })
        return sendJson(res, 200, {
          receiptId: staged.receiptId,
          name: staged.file?.name ?? name,
          bytes: staged.file?.bytes ?? null,
          requestId,
        })
      } catch (error) {
        return sendJson(res, 413, { error: 'upload-failed', message: String(error?.message || error) })
      }
    }

    const workspace = /^\/api\/sessions\/([^/]+)\/workspace$/.exec(pathname)
    if (req.method === 'GET' && workspace) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const sessionId = decodeURIComponent(workspace[1])
      const path = String(url.searchParams.get('path') || '.')
      try {
        const agent = await adapter.agentFor(sessionId)
        const entries = await options.files.listDirectory(agent, path)
        return sendJson(res, 200, { path, entries })
      } catch (error) {
        return sendJson(res, 400, { error: 'workspace-failed', message: String(error?.message || error) })
      }
    }

    const signRoute = /^\/api\/sessions\/([^/]+)\/files\/sign$/.exec(pathname)
    if (req.method === 'POST' && signRoute) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const sessionId = decodeURIComponent(signRoute[1])
      const body = JSON.parse((await readBody(req)) || '{}')
      const path = String(body.path ?? '')
      if (!path) return sendJson(res, 400, { error: 'path-required' })
      try {
        const agent = await adapter.agentFor(sessionId)
        const meta = await options.files.prepareDownload(agent, path)
        const signed = options.files.signDownload(meta, sessionId)
        state.audit({ kind: 'download-sign', detail: { sessionId, name: signed.name } })
        return sendJson(res, 200, { ...signed, url: `/api/files/${signed.token}` })
      } catch (error) {
        return sendJson(res, 400, { error: 'sign-failed', message: String(error?.message || error) })
      }
    }

    const fileRoute = /^\/api\/files\/(.+)$/.exec(pathname)
    if (req.method === 'GET' && fileRoute) {
      const payload = options.files.verify(decodeURIComponent(fileRoute[1]))
      if (!payload) return sendJson(res, 403, { error: 'invalid-token' })
      try {
        const { bytes, name } = await options.files.readSigned(payload)
        res.writeHead(200, {
          'content-type': 'application/octet-stream',
          'content-length': bytes.length,
          'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name ?? 'file')}`,
          'cache-control': 'no-store',
        })
        return res.end(Buffer.from(bytes))
      } catch (error) {
        return sendJson(res, 400, { error: 'read-failed', message: String(error?.message || error) })
      }
    }

    if (req.method === 'GET' && pathname === '/api/approvals') {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      return sendJson(res, 200, { approvals: options.listApprovals?.() ?? [] })
    }

    const approvalAnswer = /^\/api\/approvals\/([^/]+)\/answer$/.exec(pathname)
    if (req.method === 'POST' && approvalAnswer) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const body = JSON.parse((await readBody(req)) || '{}')
      const outcome = String(body.outcome ?? '')
      const id = decodeURIComponent(approvalAnswer[1])
      const accepted = options.answerApproval?.(id, outcome) ?? false
      if (accepted) state.audit({ kind: 'approval', detail: { id, outcome } })
      return sendJson(res, accepted ? 200 : 409, { accepted, outcome })
    }

    const policyRoute = /^\/api\/sessions\/([^/]+)\/approval-policy$/.exec(pathname)
    if (policyRoute) {
      if (!authorized(req)) return sendJson(res, 401, { error: 'unauthorized' })
      const sessionId = decodeURIComponent(policyRoute[1])
      try {
        if (req.method === 'GET') {
          return sendJson(res, 200, { policy: await adapter.getApprovalPolicy(sessionId) })
        }
        if (req.method === 'POST') {
          const body = JSON.parse((await readBody(req)) || '{}')
          const policy = String(body.policy ?? '')
          const applied = await adapter.setApprovalPolicy(sessionId, policy)
          state.audit({ kind: 'approval-policy', detail: { sessionId, policy: applied } })
          return sendJson(res, 200, { policy: applied })
        }
      } catch (error) {
        return sendJson(res, 503, { error: 'policy-failed', message: String(error?.message || error) })
      }
    }

    if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      return sendFile(res, 'index.html', 'text/html; charset=utf-8')
    }

    if (req.method === 'GET' && pathname === '/desktop') {
      if (!isLocalPeer(req)) return sendJson(res, 403, { error: 'loopback-only' })
      return sendFile(res, 'desktop.html', 'text/html; charset=utf-8')
    }

    if (req.method === 'GET' && pathname === '/icon.svg') {
      return sendFile(res, join('..', 'icon.svg'), 'image/svg+xml')
    }

    return sendJson(res, 404, { error: 'not-found' })
  }

  function openEventStream(req, res) {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    res.write(': connected\n\n')
    subscribers.add(res)

    const heartbeat = setInterval(() => {
      try {
        res.write(': ping\n\n')
      } catch {
        subscribers.delete(res)
      }
    }, 20000)

    const cleanup = () => {
      clearInterval(heartbeat)
      subscribers.delete(res)
    }
    req.on('close', cleanup)
    res.on('close', cleanup)
  }

  function broadcast(payload) {
    if (subscribers.size === 0) return
    const frame = `data: ${JSON.stringify(payload)}\n\n`
    for (const res of subscribers) {
      try {
        res.write(frame)
      } catch {
        subscribers.delete(res)
      }
    }
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
      for (const res of subscribers) {
        try {
          res.end()
        } catch {
          // The peer may already be gone.
        }
      }
      subscribers.clear()
      return new Promise((resolve) => server.close(() => resolve()))
    },
    broadcast,
  }
}
