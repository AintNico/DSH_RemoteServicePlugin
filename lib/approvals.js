/**
 * Approval answerer. It registers with `prepend` so it runs before the Host's
 * remote bridge that forwards the same waterfall to connected browser clients.
 *
 * P2 milestone: this currently observes and delegates (`next()`); the phone-waiting
 * branch lands with the approval UI.
 */
export const PROBE_TOOL = '__dsh_remote_probe__'

export function installApprovalObserver(ctx) {
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

    // The ordering probe answers itself so it can never reach the remote bridge
    // (which would pop a dialog for a tool that nobody asked for).
    if (request?.toolName === PROBE_TOOL) return 'rejected'
    return next()
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
