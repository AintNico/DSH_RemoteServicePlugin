import { networkInterfaces } from 'node:os'
import { createDshAdapter, isSurfaceEvent, mapSurfaceEvent } from './dsh.js'
import { createRemoteServer } from './server.js'
import { StateStore } from './state.js'

const VERSION = '0.2.0'

function lanAddress() {
  const interfaces = networkInterfaces()
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address
    }
  }
  return '127.0.0.1'
}

export function startRemoteService(ctx, options) {
  const state = new StateStore()
  const pairing = state.beginPairing(options.pairingTtlMs)
  const adapter = createDshAdapter(ctx)

  let closed = false
  let boundPort = options.port

  const server = createRemoteServer({
    state,
    adapter,
    options: {
      ...options,
      version: VERSION,
      info: () => ({ port: boundPort, lanUrl: `http://${lanAddress()}:${boundPort}`, version: VERSION }),
    },
  })

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
      server.close().catch(() => {})
      state.save()
    },
  }
}
