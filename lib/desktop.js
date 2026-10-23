import { execFile, spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const projectDir = join(root, 'app', 'desktop')
const projectFile = join(projectDir, 'DshRemote.csproj')

/** Published build first, then a local development build. */
const CANDIDATES = [
  join(projectDir, 'dist', 'DshRemote.exe'),
  join(projectDir, 'bin', 'x64', 'Release', 'net8.0-windows10.0.19041.0', 'DshRemote.exe'),
  join(projectDir, 'bin', 'x64', 'Debug', 'net8.0-windows10.0.19041.0', 'DshRemote.exe'),
]

export function findDesktopApp(override) {
  if (override && existsSync(override)) return override
  return CANDIDATES.find(candidate => existsSync(candidate)) ?? null
}

function run(command, args, timeoutMs) {
  return new Promise(resolve => {
    execFile(command, args, { timeout: timeoutMs, windowsHide: true }, (error, stdout) => {
      resolve({ ok: !error, output: String(stdout ?? '') })
    })
  })
}

export async function isDesktopRunning() {
  const { ok, output } = await run('tasklist', ['/FI', 'IMAGENAME eq DshRemote.exe', '/NH'], 8000)
  return ok && output.toLowerCase().includes('dshremote.exe')
}

export function launchDesktop(exe) {
  const child = spawn(exe, [], { detached: true, stdio: 'ignore', windowsHide: false })
  child.unref()
  return child.pid
}

/**
 * Creates the Desktop and Start Menu shortcuts once, so the app stays reachable after the
 * window is closed without re-enabling the plugin.
 */
export async function ensureShortcuts(exe) {
  const script = `
$exe = '${exe.replace(/'/g, "''")}'
$targets = @(
  (Join-Path ([Environment]::GetFolderPath('Desktop')) 'DSH 远程.lnk'),
  (Join-Path ([Environment]::GetFolderPath('Programs')) 'DSH 远程.lnk')
)
$shell = New-Object -ComObject WScript.Shell
foreach ($target in $targets) {
  if (Test-Path $target) { continue }
  $shortcut = $shell.CreateShortcut($target)
  $shortcut.TargetPath = $exe
  $shortcut.WorkingDirectory = Split-Path $exe
  $shortcut.IconLocation = "$exe,0"
  $shortcut.Description = 'DSH 远程控制'
  $shortcut.Save()
}
`
  return run('powershell', ['-NoProfile', '-NonInteractive', '-Command', script], 20000)
}

async function buildDesktop(log) {
  const { ok } = await run('dotnet', ['--version'], 8000)
  if (!ok) {
    log('未找到 dotnet SDK，无法自动构建桌面端；请先安装 .NET 8 SDK')
    return null
  }

  log('正在构建桌面端（首次约 1 分钟）…')
  const result = await run('dotnet', ['build', projectFile, '-c', 'Debug', '-p:Platform=x64'], 300000)
  if (!result.ok) {
    log('桌面端构建失败，请手动运行 dotnet build')
    return null
  }
  return findDesktopApp(null)
}

/**
 * Makes sure the desktop window is open. Safe to call repeatedly: an already running
 * instance is reused instead of spawning a second window.
 */
export async function ensureDesktopRunning(options, log = () => {}) {
  if (options?.autoLaunch === false) return

  let exe = findDesktopApp(options?.exe)
  if (!exe) {
    exe = await buildDesktop(log)
    if (!exe) return
  }

  if (await isDesktopRunning()) return

  try {
    launchDesktop(exe)
    log(`已启动桌面端：${exe}`)
  } catch (error) {
    log(`启动桌面端失败：${error.message}`)
  }

  if (options?.shortcuts !== false) {
    ensureShortcuts(exe).catch(() => {})
  }
}
