import { startRemoteService } from './lib/service.js'

/** The Session list, message history and prompt admission this bundle builds on. */
export const inject = ['sessionController', 'sessionQuery']

const DEFAULTS = {
  port: 7317,
  host: '0.0.0.0',
  pairingTtlMs: 5 * 60 * 1000,
}

export function apply(ctx, config = {}) {
  const options = { ...DEFAULTS, ...config }

  let service
  try {
    service = startRemoteService(ctx, options)
  } catch (error) {
    console.error('[remote-service] failed to start:', error)
    return
  }

  ctx.effect(() => () => service.stop())
}
