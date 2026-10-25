param(
  [string[]]$Tasks = @(':core:agent:testDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
# Preserve the native exit code even if the caller enables native error exceptions.
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $root
try {
  & (Join-Path $root 'gradlew.bat') @Tasks --console=plain
  $gradleExitCode = $LASTEXITCODE
} finally {
  Pop-Location
}
exit $gradleExitCode
