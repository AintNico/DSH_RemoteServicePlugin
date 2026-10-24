# Builds the three release artifacts into release/:
#   DSH-Remote-<version>-android.apk
#   DSH-Remote-<version>-desktop-win-x64.zip   (self-contained, no .NET runtime needed)
#   DSH-Remote-<version>-plugin.zip            (plugin sources only)
#
# Kept ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI and
# would garble non-ASCII text in this file.
param(
    [switch]$SkipDesktop,
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$version = (Get-Content package.json -Raw | ConvertFrom-Json).version
$release = Join-Path $root 'release'
New-Item -ItemType Directory -Force -Path $release | Out-Null
Write-Host "== DSH Remote $version ==" -ForegroundColor Cyan

# --- 1. Android APK ---------------------------------------------------------
$apkOut = Join-Path $release "DSH-Remote-$version-android.apk"
if (-not $SkipAndroid) {
    Write-Host 'Building APK...'
    $env:JAVA_HOME = 'D:\android\jbr'
    $gradle = 'D:\android\gradle\gradle-8.13\bin\gradle.bat'
    if (-not (Test-Path $gradle)) { throw "gradle not found at $gradle" }
    & $gradle -p (Join-Path $root 'app/android') assembleDebug --no-daemon --console=plain |
        Select-String -Pattern 'BUILD|error:' | Select-Object -Last 3
    Copy-Item (Join-Path $root 'app/android/app/build/outputs/apk/debug/app-debug.apk') $apkOut -Force
    Write-Host ("  -> {0} ({1:N0} KB)" -f $apkOut, ((Get-Item $apkOut).Length / 1KB))
} else {
    Write-Host 'Skipping APK'
}

# --- 2. Desktop (self-contained) -------------------------------------------
$desktopZip = Join-Path $release "DSH-Remote-$version-desktop-win-x64.zip"
$dist = Join-Path $root 'app/desktop/dist'
if (-not $SkipDesktop) {
    Write-Host 'Publishing desktop app (self-contained, takes a few minutes)...'
    dotnet publish (Join-Path $root 'app/desktop/DshRemote.csproj') -c Release -r win-x64 `
        --self-contained true -o $dist | Select-String -Pattern 'error|Build succeeded|succeeded' | Select-Object -Last 3

    Write-Host 'Zipping desktop app...'
    if (Test-Path $desktopZip) { Remove-Item $desktopZip -Force }
    Compress-Archive -Path (Join-Path $dist '*') -DestinationPath $desktopZip -CompressionLevel Optimal
    Write-Host ("  -> {0} ({1:N1} MB)" -f $desktopZip, ((Get-Item $desktopZip).Length / 1MB))
} else {
    Write-Host 'Skipping desktop'
}

# --- 3. Plugin sources ------------------------------------------------------
Write-Host 'Zipping plugin sources...'
$pluginZip = Join-Path $release "DSH-Remote-$version-plugin.zip"
if (Test-Path $pluginZip) { Remove-Item $pluginZip -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$exclude = @(
    'release\', '.git\', '.idea\',
    'app\desktop\bin\', 'app\desktop\obj\', 'app\desktop\dist\',
    'app\android\.gradle\', 'app\android\build\', 'app\android\app\build\',
    'node_modules\'
)
$source = (Resolve-Path $root).Path
$zip = [System.IO.Compression.ZipFile]::Open($pluginZip, 'Create')
try {
    Get-ChildItem $source -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($source.Length + 1)
        foreach ($dir in $exclude) {
            if ($relative.StartsWith($dir)) { return }
        }
        if ($relative -eq 'app\android\local.properties') { return }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host ("  -> {0} ({1:N1} MB)" -f $pluginZip, ((Get-Item $pluginZip).Length / 1MB))

Write-Host ''
Write-Host 'Done. Upload these to GitHub Releases:' -ForegroundColor Green
Get-ChildItem $release | Select-Object Name, @{ n = 'Size'; e = { "{0:N1} MB" -f ($_.Length / 1MB) } } | Format-Table -AutoSize
