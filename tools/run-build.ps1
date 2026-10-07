param(
  [Parameter(Mandatory)][ValidateSet('start','status','stop','clean-orphans')][string]$Action,
  [string]$Task = ':app:assembleDebug'
)

$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $env:TEMP 'opencode'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$log  = Join-Path $dir 'gradle-build.log'
$err  = Join-Path $dir 'gradle-build.err.log'
$pidf = Join-Path $dir 'gradle-build.pid'

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
  }

  'status' {
    $procId = if (Test-Path $pidf) { [int](Get-Content $pidf) } else { $null }
    $alive  = $procId -and [bool](Get-Process -Id $procId -ErrorAction SilentlyContinue)
    "pid=$procId alive=$alive"
    if (Test-Path $log) {
      Select-String -Path $log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|^e: |FAILURE|What went wrong' -CaseSensitive |
        Select-Object -Last 20 | ForEach-Object { $_.Line }
    }
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
