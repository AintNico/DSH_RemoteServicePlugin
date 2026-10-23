import { startRemoteService } from './lib/service.js'
import { ensureDesktopRunning } from './lib/desktop.js'

/** The Session list, message history and prompt admission this bundle builds on. */
export const inject = ['sessionController', 'sessionQuery']

const DEFAULTS = {
  port: 7317,
  host: '0.0.0.0',
  pairingTtlMs: 5 * 60 * 1000,
  desktop: { autoLaunch: true, shortcuts: true, exe: null },
}

export function apply(ctx, config = {}) {
  const options = { ...DEFAULTS, ...config, desktop: { ...DEFAULTS.desktop, ...(config.desktop ?? {}) } }

  let service
  try {
    service = startRemoteService(ctx, options)
  } catch (error) {
    console.error('[remote-service] failed to start:', error)
    return
  }

  // Opening the window is best-effort: a missing SDK or a build failure must not take the
  // remote service down with it.
  setTimeout(() => {
    ensureDesktopRunning(options.desktop, message => console.log(`[remote-service] ${message}`))
      .catch(error => console.error('[remote-service] desktop launch failed:', error))
  }, 1200)

  ctx.effect(() => () => service.stop())
}
