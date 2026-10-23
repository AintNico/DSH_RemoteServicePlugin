import { createHmac, timingSafeEqual } from 'node:crypto'

const SIGNED_TTL_MS = 5 * 60 * 1000
export const MAX_UPLOAD_BYTES = 200 * 1024 * 1024

function base64url(input) {
  return Buffer.from(input).toString('base64url')
}

/**
 * Workspace-scoped file access. Every path is resolved through DSH's own filesystem
 * service and checked against the Session's working directory, so a crafted relative
 * path cannot escape it.
 */
export function createFileService(ctx, { secret }) {
  const fs = ctx.get?.('fs') ?? ctx.fs

  function requireFs() {
    if (!fs) throw new Error('fs is not available in this profile')
    return fs
  }

  function sessionRoot(agent) {
    const cwd = agent.session.header?.cwd
    return cwd || process.cwd()
  }

  async function resolveInside(agent, relativePath) {
    const api = requireFs()
    const rootPath = sessionRoot(agent)
    const root = await api.resolve(rootPath, {})
    const target = await api.resolve(relativePath, { cwd: rootPath })
    return { api, root, target, inside: api.contains(root, target) || relativePath === '.' }
  }

  async function listDirectory(agent, relativePath = '.') {
    const { api, target, inside } = await resolveInside(agent, relativePath)
    if (!inside) throw new Error('path escapes the session workspace')
    const entries = await api.listDir(target)
    return entries
      .map((entry) => ({
        name: entry.name,
        directory: entry.type === 'directory',
        size: entry.size ?? null,
      }))
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name))
  }

  /** Validate one workspace file and describe it for a signed download. */
  async function prepareDownload(agent, relativePath) {
    const { api, target, inside } = await resolveInside(agent, relativePath)
    if (!inside) throw new Error('path escapes the session workspace')
    const info = await api.stat(target)
    if (!info || info.type !== 'file') throw new Error('not a file')
    return {
      hostPath: api.processPath(target),
      rootPath: api.processPath(await api.resolve(sessionRoot(agent), {})),
      name: relativePath.split(/[\\/]/).pop(),
      size: info.size ?? null,
    }
  }

  function sign(payload) {
    const body = base64url(JSON.stringify(payload))
    const mac = createHmac('sha256', secret).update(body).digest('base64url')
    return `${body}.${mac}`
  }

  function verify(token) {
    const [body, mac] = String(token).split('.')
    if (!body || !mac) return null
    const expected = createHmac('sha256', secret).update(body).digest('base64url')
    if (expected.length !== mac.length) return null
    if (!timingSafeEqual(Buffer.from(expected), Buffer.from(mac))) return null
    try {
      const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'))
      if (!payload?.exp || payload.exp < Date.now()) return null
      return payload
    } catch {
      return null
    }
  }

  function signDownload(meta, sessionId) {
    const exp = Date.now() + SIGNED_TTL_MS
    return {
      token: sign({
        kind: 'workspace',
        sessionId,
        hostPath: meta.hostPath,
        rootPath: meta.rootPath,
        name: meta.name,
        exp,
      }),
      expiresAt: exp,
      name: meta.name,
      size: meta.size,
    }
  }

  /** Re-check containment at download time and return the bytes. */
  async function readSigned(payload) {
    const api = requireFs()
    const root = await api.resolve(payload.rootPath, {})
    const target = await api.resolve(payload.hostPath, {})
    if (!api.contains(root, target)) throw new Error('path escapes the session workspace')
    const bytes = await api.readBytes(target, undefined, MAX_UPLOAD_BYTES)
    return { bytes, name: payload.name }
  }

  return { listDirectory, prepareDownload, signDownload, verify, readSigned }
}
