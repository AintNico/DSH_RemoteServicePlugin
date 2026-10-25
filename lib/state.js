import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const MAX_AUDIT = 2000

/** A paired device keeps working for a day, and every request pushes the window forward. */
export const TOKEN_TTL_MS = 24 * 60 * 60 * 1000

/** lastSeen changes on every request; persist at most this often. */
const SEEN_WRITE_INTERVAL_MS = 60 * 1000

function stateDirectory() {
  const base = process.env.DSH_PROFILE_DIR || join(homedir(), '.dsh', 'profiles', 'desktop')
  return join(base, 'remote-service')
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a))
  const right = Buffer.from(String(b))
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}

export function hashToken(token) {
  return createHash('sha256').update(token).digest('hex')
}

export class StateStore {
  constructor() {
    this.dir = stateDirectory()
    this.file = join(this.dir, 'state.json')
    this.data = this.#load()
    this.pairing = null
    this.lastSeenSave = 0
  }

  #load() {
    try {
      if (existsSync(this.file)) {
        const parsed = JSON.parse(readFileSync(this.file, 'utf8'))
        if (parsed && typeof parsed === 'object') {
          const devices = Array.isArray(parsed.devices) ? parsed.devices : []
          const now = Date.now()
          for (const device of devices) {
            // Entries written before device identity existed keep none, so the list can
            // still collapse them by name until the device pairs again.
            if (!device.expiresAt) device.expiresAt = (device.lastSeenAt || now) + TOKEN_TTL_MS
          }
          return {
            version: 1,
            devices,
            audit: Array.isArray(parsed.audit) ? parsed.audit : [],
            secret: typeof parsed.secret === 'string' ? parsed.secret : null,
          }
        }
      }
    } catch (error) {
      console.error('[remote-service] cannot read state, starting fresh:', error.message)
    }
    return { version: 1, devices: [], audit: [], secret: null }
  }

  /** Key used to sign short-lived download URLs; generated once and persisted. */
  get secret() {
    if (!this.data.secret) {
      this.data.secret = randomBytes(32).toString('hex')
      this.save()
    }
    return this.data.secret
  }

  save() {
    try {
      if (!existsSync(this.dir)) mkdirSync(this.dir, { recursive: true })
      const tmp = `${this.file}.tmp`
      writeFileSync(tmp, JSON.stringify(this.data, null, 2), 'utf8')
      renameSync(tmp, this.file)
    } catch (error) {
      console.error('[remote-service] cannot persist state:', error.message)
    }
  }

  /** Mint a short-lived pairing code; the previous one is discarded. */
  beginPairing(ttlMs) {
    const code = String(randomBytes(4).readUInt32BE(0) % 1000000).padStart(6, '0')
    this.pairing = { code, expiresAt: Date.now() + ttlMs }
    return this.pairing
  }

  pairingSnapshot() {
    if (!this.pairing || this.pairing.expiresAt <= Date.now()) return null
    return { ...this.pairing }
  }

  /**
   * Redeem a pairing code. A device that already exists (matched by its stable id) refreshes
   * in place — new token, new window, same row — so the management list never shows duplicates.
   */
  consumePairingCode(code, deviceName, deviceId) {
    if (!this.pairing) return { ok: false, reason: 'not-pairing' }
    if (this.pairing.expiresAt <= Date.now()) {
      this.pairing = null
      return { ok: false, reason: 'expired' }
    }
    if (!safeEqual(String(code ?? ''), this.pairing.code)) {
      return { ok: false, reason: 'bad-code' }
    }

    this.pairing = null
    const now = Date.now()
    const token = randomBytes(32).toString('base64url')
    const tokenHash = hashToken(token)
    const name = String(deviceName || 'Android device').slice(0, 64)
    const key = String(deviceId || '').slice(0, 64)

    let device = key ? this.data.devices.find((entry) => entry.deviceId === key) : undefined
    if (!device) {
      // A client that never sent an id (or a row from before ids existed): reuse the most
      // recent entry with the same display name so the list does not grow a duplicate.
      device = this.data.devices
        .filter((entry) => !entry.deviceId && entry.name === name)
        .sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0))[0]
    }
    const reused = Boolean(device)
    if (device) {
      if (key) device.deviceId = key
      device.tokenHash = tokenHash
      device.name = name
      device.lastSeenAt = now
      device.expiresAt = now + TOKEN_TTL_MS
    } else {
      device = {
        id: randomBytes(8).toString('hex'),
        deviceId: key || randomBytes(8).toString('hex'),
        name,
        tokenHash,
        createdAt: now,
        lastSeenAt: now,
        expiresAt: now + TOKEN_TTL_MS,
      }
      this.data.devices.push(device)
    }

    this.#pruneExpired(now)
    this.save()
    return { ok: true, token, reused, device: this.#publicDevice(device) }
  }

  /**
   * Look up a token and slide its window forward. An expired token resolves to undefined, so
   * the caller answers 401 and the phone falls back to pairing.
   */
  deviceForToken(token) {
    const hash = hashToken(String(token || ''))
    const now = Date.now()
    const device = this.data.devices.find((entry) => safeEqual(entry.tokenHash, hash))
    if (!device) return undefined
    if (device.expiresAt && device.expiresAt <= now) return undefined

    device.lastSeenAt = now
    device.expiresAt = now + TOKEN_TTL_MS
    if (now - this.lastSeenSave > SEEN_WRITE_INTERVAL_MS) {
      this.lastSeenSave = now
      this.save()
    }
    return device
  }

  revokeDevice(id) {
    const before = this.data.devices.length
    this.data.devices = this.data.devices.filter((device) => device.id !== id)
    if (this.data.devices.length !== before) {
      this.save()
      return true
    }
    return false
  }

  /** One row per physical device, newest activity first; expired tokens are dropped. */
  listDevices() {
    this.#pruneExpired()
    const byDevice = new Map()
    for (const device of this.data.devices) {
      const key = device.deviceId || `name:${device.name}`
      const current = byDevice.get(key)
      if (!current || (device.lastSeenAt || 0) > (current.lastSeenAt || 0)) {
        byDevice.set(key, device)
      }
    }
    return [...byDevice.values()]
      .sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0))
      .map((device) => this.#publicDevice(device))
  }

  #pruneExpired(now = Date.now()) {
    const before = this.data.devices.length
    this.data.devices = this.data.devices.filter(
      (device) => !device.expiresAt || device.expiresAt > now,
    )
    return before - this.data.devices.length
  }

  audit(entry) {
    this.data.audit.push({ at: Date.now(), ...entry })
    if (this.data.audit.length > MAX_AUDIT) {
      this.data.audit = this.data.audit.slice(-MAX_AUDIT)
    }
  }

  #publicDevice(device) {
    return {
      id: device.id,
      name: device.name,
      createdAt: device.createdAt,
      lastSeenAt: device.lastSeenAt,
      expiresAt: device.expiresAt,
    }
  }
}
