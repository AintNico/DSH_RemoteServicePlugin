import { randomUUID } from 'node:crypto'

/**
 * Adapter over the DSH host services this plugin depends on.
 * Everything DSH-specific lives here so an upstream change only touches one file.
 */
export function createDshAdapter(ctx) {
  const controller = ctx.get?.('sessionController') ?? ctx.sessionController
  const query = ctx.get?.('sessionQuery') ?? ctx.sessionQuery

  function requireController() {
    if (!controller) throw new Error('sessionController is not available in this profile')
    return controller
  }

  async function listSessions() {
    const value = await requireController().list({}, AbortSignal.timeout(5000))
    return (value?.items ?? []).map((summary) => ({
      id: summary.sessionId,
      running: Boolean(summary.running),
      blank: Boolean(summary.blank),
      updatedAt: summary.updatedAt ?? null,
      cwd: summary.cwd ?? null,
      origin: summary.origin ?? null,
    }))
  }

  async function readMessages(sessionId) {
    if (!query) throw new Error('sessionQuery is not available in this profile')
    const surface = await query.readSurface(sessionId)
    const messages = (surface?.events ?? [])
      .map((event) => mapSurfaceEvent(event))
      .filter(Boolean)
    return { messages, throughSeq: surface?.capturedThroughSeq ?? null }
  }

  async function sendMessage(sessionId, text) {
    const requestId = randomUUID()
    await requireController().prompt(
      {
        requestId,
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text }],
      },
      AbortSignal.timeout(15000),
    )
    return requestId
  }

  function cancelTurn(sessionId) {
    return requireController().cancel({ sessionId })
  }

  return { listSessions, readMessages, sendMessage, cancelTurn }
}

const SURFACE_TYPES = new Set(['user/message', 'assistant/message', 'tool/result'])

export function isSurfaceEvent(type) {
  return SURFACE_TYPES.has(type)
}

/** Normalise one session event into the wire shape the clients render. */
export function mapSurfaceEvent(event, sessionId) {
  const base = { sessionId: sessionId ?? null, seq: event.seq, time: event.time }

  if (event.type === 'user/message') {
    return { ...base, role: 'user', text: flattenContent(event.data?.content) }
  }

  if (event.type === 'assistant/message') {
    const text = flattenContent(event.data?.message?.content)
    if (!text) return null
    return {
      ...base,
      role: 'assistant',
      text,
      interrupted: Boolean(event.data?.interrupted),
    }
  }

  if (event.type === 'tool/result') {
    const message = event.data?.message
    const text = flattenContent(message?.content)
    return {
      ...base,
      role: 'tool',
      text: text.length > 400 ? `${text.slice(0, 400)}…` : text,
      isError: Boolean(message?.isError ?? event.data?.error),
    }
  }

  return null
}

function flattenContent(content) {
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (!block || typeof block !== 'object') continue
    switch (block.type) {
      case 'text':
        if (block.text) parts.push(block.text)
        break
      case 'image':
        parts.push('[图片]')
        break
      case 'file':
        parts.push(`[文件 ${block.attachment?.name ?? ''}]`.replace(' ]', ']'))
        break
      case 'tool-call':
        parts.push(`[调用工具 ${block.name ?? ''}]`.replace(' ]', ']'))
        break
      default:
        break
    }
  }
  return parts.join('\n').trim()
}
