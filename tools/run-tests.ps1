param(
  [string]$Tasks = ':core:agent:testDebugUnitTest',
  [int]$TimeoutSec = 300
)

$root = Split-Path -Parent $PSScriptRoot
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'GradleDaemon|KotlinCompileDaemon' } |
  ForEach-Object { taskkill /PID $_.ProcessId /T /F | Out-Null }

# MUST match org.gradle.jvmargs exactly (see gradle.properties) - otherwise
# Gradle forks a single-use daemon instead of building in the client JVM.
$env:GRADLE_OPTS = "-Xmx4g -Xms64m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError --add-modules=ALL-DEFAULT"

$log = Join-Path $env:TEMP 'opencode\gradle-tests.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null

$cmd = "`"`"$root\gradlew.bat`" $Tasks --no-daemon --console=plain > `"$log`" 2>&1`""
$p = Start-Process -FilePath $env:ComSpec -ArgumentList '/c', $cmd -PassThru -WindowStyle Hidden

$code = $p.WaitForExit($TimeoutSec * 1000)
if (-not $code) {
  taskkill /PID $p.Id /T /F | Out-Null
  "TIMEOUT after $TimeoutSec s - see $log"
  exit 124
}
$p.WaitForExit()
"exit=$($p.ExitCode)"
Select-String -Path $log -Pattern '^e: |FAILED|tests completed|BUILD ' -CaseSensitive |
  ForEach-Object { $_.Line } | Select-Object -Last 20
