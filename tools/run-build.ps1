param(
  [ValidateSet('start','release','verify-release')][string]$Action = 'start',
  [string[]]$Task = @(':app:assembleDebug'),
  [string]$SdkRoot
)

$ErrorActionPreference = 'Stop'
# Preserve the native exit code even if the caller enables native error exceptions.
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot

# 'release' retains the explicit signed-build shortcut.
if ($Action -eq 'release') {
  if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    throw 'keystore.properties not found - run tools\gen-keystore.ps1 first'
  }
  $Task = @(':app:assembleRelease')
}

if ($Action -eq 'verify-release') {
  $apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
  if (-not (Test-Path $apk)) { throw "APK not found: $apk (run 'release' first)" }
  # Gradle also accepts sdk.dir in local.properties; pass -SdkRoot here in that case.
  if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
  if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
  if (-not $SdkRoot) {
    throw 'Android SDK not configured: set ANDROID_HOME (or ANDROID_SDK_ROOT), or pass -SdkRoot to verify-release'
  }
  $bt = Join-Path $SdkRoot 'build-tools'
  $ver = Get-ChildItem $bt -Directory -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
           Sort-Object { [version]$_.Name } | Select-Object -Last 1
  if (-not $ver) { throw "no build-tools under $bt" }
  $apksigner = Join-Path $ver.FullName 'apksigner.bat'
  if (-not (Test-Path $apksigner)) { throw "apksigner not found: $apksigner" }
  & $apksigner verify --print-certs $apk
  if ($LASTEXITCODE -ne 0) { throw "signature verification failed: $LASTEXITCODE" }
  'signature OK'
  return
}

Push-Location -LiteralPath $root
try {
  & (Join-Path $root 'gradlew.bat') @Task --console=plain
  $gradleExitCode = $LASTEXITCODE
} finally {
  Pop-Location
}
exit $gradleExitCode
