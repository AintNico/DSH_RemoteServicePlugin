import { networkInterfaces } from 'node:os'
import { createApprovalBroker, describeApprovalHooks, installApprovalObserver, PROBE_TOOL } from './approvals.js'
import { createDshAdapter, isSurfaceEvent, mapSurfaceEvent } from './dsh.js'
import { createFileService, MAX_UPLOAD_BYTES } from './files.js'
import { createRemoteServer } from './server.js'
import { StateStore } from './state.js'

const VERSION = '1.0.0'

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
      startStreamDemo: (sessionId) => {
        const target = sessionId || 'demo-session'
        const text = '这是流式输出的演示：每个片段单独推送，客户端逐段追加显示，最后被正式消息替换。'
        server.broadcast({ kind: 'stream-start', sessionId: target })
        let index = 0
        const timer = setInterval(() => {
          if (index >= text.length) {
            clearInterval(timer)
            server.broadcast({ kind: 'stream-end', sessionId: target, committed: false })
            return
          }
          server.broadcast({ kind: 'stream', sessionId: target, text: text.slice(index, index + 2) })
          index += 2
        }, 80)
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

  // Token-level feed: the model stream is published before the message commits, so the
  // clients can render text as it arrives instead of waiting for the whole block.
  const offStream = ctx.on('agent/assistant-stream', (payload) => {
    const agent = payload?.agent
    const frame = payload?.frame
    if (!agent || !frame) return

    if (frame.type === 'start') {
      server.broadcast({ kind: 'stream-start', sessionId: agent.id, attempt: frame.attemptId ?? null })
      return
    }
    if (frame.type === 'chunk') {
      const chunk = frame.chunk
      if (chunk?.type === 'text-delta' && typeof chunk.text === 'string' && chunk.text.length > 0) {
        server.broadcast({ kind: 'stream', sessionId: agent.id, text: chunk.text })
      }
      return
    }
    if (frame.type === 'end') {
      server.broadcast({
        kind: 'stream-end',
        sessionId: agent.id,
        committed: frame.outcome?.kind === 'committed',
      })
    }
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
      offStream?.()
      approvals?.dispose()
      broker?.dispose()
      server.close().catch(() => {})
      state.save()
    },
  }
}
