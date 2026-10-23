import { networkInterfaces } from 'node:os'
import { createApprovalBroker, describeApprovalHooks, installApprovalObserver, PROBE_TOOL } from './approvals.js'
import { createDshAdapter, isSurfaceEvent, mapSurfaceEvent } from './dsh.js'
import { createFileService, MAX_UPLOAD_BYTES } from './files.js'
import { createRemoteServer } from './server.js'
import { StateStore } from './state.js'

const VERSION = '0.3.0'

function lanAddress() {
  const interfaces = networkInterfaces()
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address
    }
  }
  return '127.0.0.1'
}

/**
 * Dynamic ordering check: dispatch one real `approval/request` waterfall through a
 * live agent and report whether this plugin's answerer observed it. The probe tool
 * name is answered by our own listener, so it never reaches a connected browser.
 */
async function probeApproval(ctx, approvals) {
  const controller = ctx.get?.('sessionController') ?? ctx.sessionController
  const approval = ctx.get?.('approval') ?? ctx.approval
  if (!controller || !approval) return { ok: false, error: 'service-unavailable' }

  const created = await controller.create({})
  const sessionId = created?.sessionId
  if (!sessionId) return { ok: false, error: 'session-create-failed' }

  const resolved = await controller.resolveAgent(sessionId)
  if (resolved?.error) return { ok: false, error: String(resolved.error), sessionId }

  const agent = resolved.agent
  const previousPolicy = approval.effectivePolicy?.(agent.session) ?? null
  const restore = () => {
    if (previousPolicy && previousPolicy !== 'ask') {
      try {
        approval.setPolicy(agent, previousPolicy)
      } catch {
        // The probe session is disposable; a failed restore is not fatal.
      }
    }
  }

  const before = approvals.recent.length
  let outcome
  try {
    if (previousPolicy !== 'ask') approval.setPolicy(agent, 'ask')
    outcome = await approval.decide(
      { agent, toolName: PROBE_TOOL, reason: 'ordering probe' },
      agent.session,
    )
  } finally {
    restore()
  }

  return {
    ok: true,
    sessionId,
    outcome,
    observed: approvals.recent.length > before,
    policy: previousPolicy,
  }
}

export function startRemoteService(ctx, options) {
  const state = new StateStore()
  const pairing = state.beginPairing(options.pairingTtlMs)
  const adapter = createDshAdapter(ctx)
  const files = createFileService(ctx, { secret: state.secret })

  let closed = false
  let boundPort = options.port
  let broker
  let approvals

  const server = createRemoteServer({
    state,
    adapter,
    options: {
      ...options,
      version: VERSION,
      info: () => ({ port: boundPort, lanUrl: `http://${lanAddress()}:${boundPort}`, version: VERSION }),
      describeApprovalHooks: () => describeApprovalHooks(ctx),
      recentApprovals: () => approvals?.recent.slice(-20) ?? [],
      probeApproval: () => probeApproval(ctx, approvals),
      startApprovalDemo: (sessionId) => {
        broker.request(
          {
            agent: { id: sessionId || 'demo-session' },
            toolName: 'sandbox-escalation',
            reason: '需要写入工作区之外的文件',
            displayReason: { en: 'Needs write access outside the workspace' },
          },
          async () => 'rejected',
        )
      },
      answerApproval: (id, outcome) => broker?.answer(id, outcome) ?? false,
      listApprovals: () => broker?.list() ?? [],
      files,
      maxUploadBytes: MAX_UPLOAD_BYTES,
    },
  })

  broker = createApprovalBroker({
    broadcast: (payload) => server.broadcast(payload),
    timeoutMs: options.approvalTimeoutMs ?? 30_000,
    retryAtMs: options.approvalRetryMs ?? 10_000,
  })
  approvals = installApprovalObserver(ctx, broker)

  // Live feed: every committed surface event is fanned out to connected clients.
  const off = ctx.on('session/event', (session, event) => {
    if (!isSurfaceEvent(event.type)) return
    const payload = mapSurfaceEvent(event, session.id)
    if (payload) server.broadcast(payload)
  })

  server.listen().then(
    (address) => {
      boundPort = address.port
      const host = lanAddress()
      console.log(`[remote-service] listening on http://${host}:${address.port}`)
      console.log(`[remote-service] pairing code ${pairing.code} (valid ${Math.round(options.pairingTtlMs / 60000)} min)`)
      console.log(`[remote-service] open http://127.0.0.1:${address.port}/desktop on this computer`)
    },
    (error) => {
      console.error(`[remote-service] cannot listen on ${options.host}:${options.port}:`, error.message)
      closed = true
    },
  )

  return {
    stop() {
      if (closed) return
      closed = true
      off?.()
      approvals?.dispose()
      broker?.dispose()
      server.close().catch(() => {})
      state.save()
    },
  }
}
