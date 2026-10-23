import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const MAX_AUDIT = 2000

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
  }

  #load() {
    try {
      if (existsSync(this.file)) {
        const parsed = JSON.parse(readFileSync(this.file, 'utf8'))
        if (parsed && typeof parsed === 'object') {
          return {
            version: 1,
            devices: Array.isArray(parsed.devices) ? parsed.devices : [],
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

  consumePairingCode(code, deviceName) {
    if (!this.pairing) return { ok: false, reason: 'not-pairing' }
    if (this.pairing.expiresAt <= Date.now()) {
      this.pairing = null
      return { ok: false, reason: 'expired' }
    }
    if (!safeEqual(String(code ?? ''), this.pairing.code)) {
      return { ok: false, reason: 'bad-code' }
    }

    this.pairing = null
    const token = randomBytes(32).toString('base64url')
    const device = {
      id: randomBytes(8).toString('hex'),
      name: String(deviceName || 'Android device').slice(0, 64),
      tokenHash: hashToken(token),
      createdAt: Date.now(),
      lastSeenAt: Date.now(),
    }
    this.data.devices.push(device)
    this.save()
    return { ok: true, token, device: this.#publicDevice(device) }
  }

  deviceForToken(token) {
    const hash = hashToken(token)
    const device = this.data.devices.find((entry) => safeEqual(entry.tokenHash, hash))
    if (device) device.lastSeenAt = Date.now()
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

  listDevices() {
    return this.data.devices.map((device) => this.#publicDevice(device))
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
    }
  }
}
