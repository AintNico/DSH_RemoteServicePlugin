import { randomUUID } from 'node:crypto'

export const PROBE_TOOL = '__dsh_remote_probe__'

/**
 * Holds one pending approval per request id and drives the phone-facing state machine:
 * push at t=0, re-push at t=retryAtMs, hand back to the next answerer at t=timeoutMs.
 * A client answer may arrive at any point before the timeout.
 */
export function createApprovalBroker({ broadcast, timeoutMs = 30_000, retryAtMs = 10_000 }) {
  const pending = new Map()

  function frame(entry, extra = {}) {
    return {
      kind: 'approval',
      id: entry.id,
      sessionId: entry.sessionId,
      toolName: entry.toolName,
      reason: entry.reason,
      displayReason: entry.displayReason,
      expiresAt: entry.expiresAt,
      ...extra,
    }
  }

  function settle(id, outcome, mode) {
    const entry = pending.get(id)
    if (!entry || entry.settled) return false

    entry.settled = true
    clearTimeout(entry.timeout)
    clearTimeout(entry.retry)
    pending.delete(id)
    broadcast({ kind: 'approval-resolved', id, outcome: mode === 'next' ? 'delegated' : outcome })

    if (mode === 'next') {
      Promise.resolve()
        .then(() => entry.next())
        .then(entry.resolve, entry.reject)
    } else {
      entry.resolve(outcome)
    }
    return true
  }

  /** Register one pending request; resolves with the outcome that ends the waterfall. */
  function request(req, next) {
    const id = randomUUID()
    const expiresAt = Date.now() + timeoutMs

    return new Promise((resolve, reject) => {
      const entry = {
        id,
        sessionId: req.agent?.id ?? null,
        toolName: req.toolName ?? null,
        reason: req.reason ?? null,
        displayReason: req.displayReason ?? null,
        expiresAt,
        next,
        resolve,
        reject,
        settled: false,
        timeout: null,
        retry: null,
      }
      pending.set(id, entry)

      broadcast(frame(entry))

      entry.retry = setTimeout(() => {
        if (!entry.settled) broadcast(frame(entry, { retry: true }))
      }, retryAtMs)

      entry.timeout = setTimeout(() => settle(id, null, 'next'), timeoutMs)

      req.signal?.addEventListener(
        'abort',
        () => {
          if (settle(id, 'cancelled', 'value')) {
            broadcast({ kind: 'approval-resolved', id, outcome: 'cancelled' })
          }
        },
        { once: true },
      )
    })
  }

  /** Called by a client. Returns false when the request already settled. */
  function answer(id, outcome) {
    if (outcome !== 'allowed-once' && outcome !== 'rejected') return false
    return settle(id, outcome, 'value')
  }

  function list() {
    return [...pending.values()].map((entry) => frame(entry))
  }

  function dispose() {
    for (const entry of [...pending.keys()]) settle(entry, null, 'next')
    pending.clear()
  }

  return { request, answer, list, dispose }
}

/**
 * The answerer registered on `approval/request`. `prepend` puts it ahead of the
 * Remote bridge that forwards the same waterfall to browser clients, so this
 * plugin decides first and only delegates on timeout.
 */
export function installApprovalObserver(ctx, broker) {
  const recent = []

  function remoteApprovalAnswerer(request, next) {
    recent.push({
      at: Date.now(),
      toolName: request?.toolName ?? null,
      callId: request?.callId ?? null,
      reason: request?.reason ?? null,
      agent: request?.agent?.id ?? null,
    })
    if (recent.length > 100) recent.shift()

    // The ordering probe answers itself so it can never reach the remote bridge.
    if (request?.toolName === PROBE_TOOL) return 'rejected'
    return broker.request(request, next)
  }

  const dispose = ctx.on('approval/request', remoteApprovalAnswerer, { prepend: true })

  return {
    recent,
    dispose: () => dispose?.(),
  }
}

/** Read-only view of who answers `approval/request`, in dispatch order. */
export function describeApprovalHooks(ctx) {
  const hooks = ctx.events?._hooks?.['approval/request'] ?? []
  return hooks.map((hook, index) => ({
    index,
    name: hook.callback?.name || '(anonymous)',
    ours: String(hook.callback?.name || '').includes('remoteApprovalAnswerer'),
    prepend: Boolean(hook.prepend),
    global: Boolean(hook.global),
  }))
}
