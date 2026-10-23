import { networkInterfaces } from 'node:os'
import { createRemoteServer } from './server.js'
import { StateStore } from './state.js'

function lanAddress() {
  const interfaces = networkInterfaces()
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address
    }
  }
  return '127.0.0.1'
}

function mapSession(summary) {
  return {
    id: summary.sessionId,
    running: Boolean(summary.running),
    blank: Boolean(summary.blank),
    updatedAt: summary.updatedAt ?? null,
    cwd: summary.cwd ?? null,
    origin: summary.origin ?? null,
  }
}

export function startRemoteService(ctx, options) {
  const state = new StateStore()
  const pairing = state.beginPairing(options.pairingTtlMs)

  const controller = ctx.get?.('sessionController') ?? ctx.sessionController

  let closed = false
  let boundPort = options.port

  const server = createRemoteServer({
    state,
    options: {
      ...options,
      version: '0.1.0',
      info: () => ({ port: boundPort, lanUrl: `http://${lanAddress()}:${boundPort}`, version: '0.1.0' }),
    },
    async listSessions() {
      if (!controller) throw new Error('sessionController is not available in this profile')
      const value = await controller.list({}, AbortSignal.timeout(5000))
      return (value?.items ?? []).map(mapSession)
    },
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
      server.close().catch(() => {})
      state.save()
    },
  }
}
