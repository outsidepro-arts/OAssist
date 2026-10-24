param(
  [Parameter(Mandatory)][ValidateSet('start','status','stop','clean-orphans','release','verify-release')][string]$Action,
  [string]$Task = ':app:assembleDebug'
)

$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $env:TEMP 'opencode'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$log  = Join-Path $dir 'gradle-build.log'
$err  = Join-Path $dir 'gradle-build.err.log'
$pidf = Join-Path $dir 'gradle-build.pid'

# 'release' = signed :app:assembleRelease (requires keystore.properties).
if ($Action -eq 'release') {
  if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    throw 'keystore.properties not found - run tools\gen-keystore.ps1 first'
  }
  $Task   = ':app:assembleRelease'
  $Action = 'start'
}
$releaseTask = ($Task -eq ':app:assembleRelease')

function Kill-OrphanDaemons {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'GradleDaemon|KotlinCompileDaemon' } |
    ForEach-Object { taskkill /PID $_.ProcessId /T /F | Out-Null }
}

switch ($Action) {
  'clean-orphans' { Kill-OrphanDaemons; 'orphans cleaned' }

  'start' {
    Kill-OrphanDaemons
    Remove-Item $log, $err, $pidf -ErrorAction SilentlyContinue

    # MUST match org.gradle.jvmargs exactly (see gradle.properties) - otherwise
    # Gradle forks a single-use daemon instead of building in the client JVM.
    $env:GRADLE_OPTS  = "-Xmx4g -Xms64m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError --add-modules=ALL-DEFAULT"
    $env:JAVA_HOME    = 'C:\dev\jdk\jdk-21.0.10+7'
    $env:ANDROID_HOME = 'c:\dev\android-sdk'
    $env:ANDROID_SDK_ROOT = 'c:\dev\android-sdk'

    $cmd = "`"`"$root\gradlew.bat`" $Task --no-daemon --console=plain > `"$log`" 2> `"$err`"`""
    $p = Start-Process -FilePath $env:ComSpec `
         -ArgumentList '/c', $cmd `
         -PassThru -WindowStyle Hidden
    $p.Id | Set-Content $pidf
    "started pid=$($p.Id) task=$Task log=$log"
    if ($releaseTask) { "apk: $root\app\build\outputs\apk\release\app-release.apk" }
  }

  'status' {
    $procId = if (Test-Path $pidf) { [int](Get-Content $pidf) } else { $null }
    $alive  = $procId -and [bool](Get-Process -Id $procId -ErrorAction SilentlyContinue)
    "pid=$procId alive=$alive"
    if (Test-Path $log) {
      Select-String -Path $log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|^e: |FAILURE|What went wrong' -CaseSensitive |
        Select-Object -Last 20 | ForEach-Object { $_.Line }
    }
    $apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
    if (Test-Path $apk) {
      $f = Get-Item $apk
      "release apk: $f ($([math]::Round($f.Length / 1MB, 1)) MB, $($f.LastWriteTime))"
    }
  }

  'verify-release' {
    $apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { throw "APK not found: $apk (run 'release' first)" }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'c:\dev\android-sdk' }
    $bt  = Join-Path $sdk 'build-tools'
    $ver = Get-ChildItem $bt -Directory -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
             Sort-Object { [version]$_.Name } | Select-Object -Last 1
    if (-not $ver) { throw "no build-tools under $bt" }
    $apksigner = Join-Path $ver.FullName 'apksigner.bat'
    if (-not (Test-Path $apksigner)) { throw "apksigner not found: $apksigner" }
    & $apksigner verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw "signature verification failed: $LASTEXITCODE" }
    'signature OK'
  }

  'stop' {
    if (Test-Path $pidf) {
      $procId = [int](Get-Content $pidf)
      taskkill /PID $procId /T /F | Out-Null
      Kill-OrphanDaemons
      "killed pid=$procId"
    } else { 'no pid' }
  }
}
