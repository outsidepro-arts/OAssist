param(
  [Parameter(Mandatory = $true)][ValidatePattern('^emulator-\d+$')][string]$Serial
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$package = 'ru.outsideproarts.oassist'
$testClass = "$package.permission.SystemPermissionLifecycleTest"
$chatTestClass = "$package.chat.ChatTogglePermissionTest"
$holdTestClass = "$package.chat.ChatHoldPermissionTest"
$permissions = @('android.permission.CAMERA', 'android.permission.RECORD_AUDIO')
$overlayTestClass = "$package.agent.AssistantOverlayModeTest"
$terminalTestClass = "$package.agent.TerminalTranscriptPersistenceTest"
$replyTestClass = "$package.notify.NotifyReplyContractInstrumentedTest"
$replyFixture = "$package.test/$package.notify.ReplyNotificationFixture"
$listener = "$package/$package.notify.OAssistNotificationListener"

function Invoke-Adb([string[]]$Arguments) {
  $output = @(& adb -s $Serial @Arguments 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output -join "`n")" }
  return ($output -join "`n")
}

function Get-PermissionState([string]$Permission, [string]$User, [string]$PackageName = $package) {
  $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $PackageName)
  # dumpsys reports per-user grants under User N; accept only the current user's section.
  $section = [regex]::Match($dump, "(?ms)^\s*User ${User}:.*?(?=^\s*User \d+:|\z)")
  if (-not $section.Success) { throw "Missing package state for user $User" }
  $line = [regex]::Match($section.Value, "(?m)^\s*$([regex]::Escape($Permission)):\s*granted=(true|false),\s*flags=\[([^\]]*)\]")
  if (-not $line.Success) { throw "Missing runtime permission state: $Permission" }
  $flags = @($line.Groups[2].Value -split '\|' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
  $supported = @('USER_SET', 'USER_FIXED', 'USER_SENSITIVE_WHEN_GRANTED', 'USER_SENSITIVE_WHEN_DENIED')
  if (@($flags | Where-Object { $_ -notin $supported }).Count) { throw "Unsupported flags for ${Permission}: $($flags -join '|')" }
  return @{ Granted = $line.Groups[1].Value -eq 'true'; UserSet = 'USER_SET' -in $flags; UserFixed = 'USER_FIXED' -in $flags; Flags = ($flags | Sort-Object) -join '|' }
}

function Set-PermissionState([string]$Permission, [string]$User, [hashtable]$State, [string]$PackageName = $package) {
  Invoke-Adb @('shell', 'pm', 'clear-permission-flags', '--user', $User, $PackageName, $Permission, 'user-set', 'user-fixed') | Out-Null
  $action = if ($State.Granted) { 'grant' } else { 'revoke' }
  Invoke-Adb @('shell', 'pm', $action, '--user', $User, $PackageName, $Permission) | Out-Null
  if ($State.UserSet) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $User, $PackageName, $Permission, 'user-set') | Out-Null }
  if ($State.UserFixed) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $User, $PackageName, $Permission, 'user-fixed') | Out-Null }
  $actual = Get-PermissionState $Permission $User $PackageName
  foreach ($key in @('Granted', 'UserSet', 'UserFixed')) {
    if ($actual[$key] -ne $State[$key]) { throw "Failed to restore $Permission $key" }
  }
  if ($State.ContainsKey('Flags') -and $actual.Flags -ne $State.Flags) { throw "Failed to restore $Permission flags" }
}

function Get-Listeners([string]$User) {
  $value = (Invoke-Adb @('shell', 'settings', '--user', $User, 'get', 'secure', 'enabled_notification_listeners')).Trim()
  if ($value -eq 'null' -or !$value) { return @() }
  return @($value -split ':' | ForEach-Object {
    if ($_ -notmatch '^([^/]+)/([^/]+)$') { throw "Invalid listener component: $_" }
    $componentPackage = $Matches[1]
    $componentClass = $Matches[2]
    if ($componentClass.StartsWith('.')) { $componentClass = "$componentPackage$componentClass" }
    "$componentPackage/$componentClass"
  } | Sort-Object -Unique)
}

function Wait-Listeners([string]$User, [string[]]$Expected) {
  # NotificationManager persists this setting asynchronously after allow/disallow.
  $expectedValue = (@($Expected | Sort-Object -Unique) -join ':')
  $deadline = [Diagnostics.Stopwatch]::StartNew()
  do {
    if ((@(Get-Listeners $User) -join ':') -ceq $expectedValue) { return }
    Start-Sleep -Milliseconds 100
  } while ($deadline.Elapsed.TotalSeconds -lt 5)
  throw 'Notification listener access did not reach the expected state within 5 seconds'
}

if ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') { throw 'Only an emulator is supported' }
$user = (Invoke-Adb @('shell', 'am', 'get-current-user')).Trim()
if ($user -notmatch '^\d+$') { throw "Cannot identify current Android user: $user" }
$overlay = Invoke-Adb @('shell', 'appops', 'get', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW')
if ($overlay -notmatch 'SYSTEM_ALERT_WINDOW: (deny|ignore|default)' -and $overlay -notmatch 'No operations') {
  throw "Overlay must be disabled: $overlay"
}
$overlayMode = if ($overlay -match 'SYSTEM_ALERT_WINDOW: (deny|ignore|default)') { $Matches[1] } else { 'default' }
$sdk = [int](Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
if ($sdk -ge 33) {
  $permissions += 'android.permission.POST_NOTIFICATIONS'
}
$states = @{}
foreach ($permission in $permissions) { $states[$permission] = Get-PermissionState $permission $user }
$listeners = @(Get-Listeners $user)
$testNotificationState = if ($sdk -ge 33) { Get-PermissionState 'android.permission.POST_NOTIFICATIONS' $user "$package.test" } else { $null }
$replyPhaseStarted = $false
$failure = $null
$restoreFailures = @()
try {
  foreach ($permission in @('android.permission.CAMERA', 'android.permission.RECORD_AUDIO')) { Set-PermissionState $permission $user @{ Granted = $false; UserSet = $false; UserFixed = $false } }
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'systemPermissionFixture', 'true', '-e', 'class', "$testClass,$chatTestClass", "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(6 tests\)\s*$') { throw 'Instrumentation did not report six passing tests' }
  Set-PermissionState 'android.permission.RECORD_AUDIO' $user @{ Granted = $false; UserSet = $false; UserFixed = $false }
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'systemPermissionFixture', 'true', '-e', 'class', $holdTestClass, "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(1 test\)\s*$') { throw 'Hold permission instrumentation did not report one passing test' }
  Invoke-Adb @('shell', 'appops', 'set', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW', 'allow') | Out-Null
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'overlayModeFixture', 'true', '-e', 'class', $overlayTestClass, "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(1 test\)\s*$') { throw 'Overlay mode instrumentation did not report one passing test' }
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'class', $terminalTestClass, "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(16 tests\)\s*$') { throw 'Terminal transcript instrumentation did not report sixteen passing tests' }
  $replyPhaseStarted = $true
  if ($sdk -ge 33) { Set-PermissionState 'android.permission.POST_NOTIFICATIONS' $user @{ Granted = $true; UserSet = $false; UserFixed = $false } "$package.test" }
  # Instrumentation restarts the target process. The fixture grants its listener
  # only after that restart, so Android binds the live instrumentation process.
  Invoke-Adb @('shell', 'cmd', 'notification', 'disallow_listener', $listener, $user) | Out-Null
  Wait-Listeners $user @($listeners | Where-Object { $_ -ne $listener })
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'notificationReplyUser', $user, '-e', 'class', $replyTestClass, "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(5 tests\)\s*$') { throw 'Notification reply instrumentation did not report five passing tests' }
} catch { $failure = $_ }
finally {
  if ($replyPhaseStarted) {
    try { Invoke-Adb @('shell', 'am', 'broadcast', '--user', $user, '-a', 'clear', '-n', $replyFixture) | Out-Null } catch { $restoreFailures += $_ }
    try {
      $listenerAction = if ($listener -in $listeners) { 'allow_listener' } else { 'disallow_listener' }
      Invoke-Adb @('shell', 'cmd', 'notification', $listenerAction, $listener, $user) | Out-Null
      Wait-Listeners $user $listeners
    } catch { $restoreFailures += $_ }
    if ($testNotificationState) {
      try { Set-PermissionState 'android.permission.POST_NOTIFICATIONS' $user $testNotificationState "$package.test" } catch { $restoreFailures += $_ }
    }
  }
  try {
    Invoke-Adb @('shell', 'appops', 'set', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW', $overlayMode) | Out-Null
    $restored = Invoke-Adb @('shell', 'appops', 'get', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW')
    if ($restored -notmatch "SYSTEM_ALERT_WINDOW: $overlayMode" -and !($overlayMode -eq 'default' -and $restored -match 'No operations')) { throw "Failed to restore overlay app-op: $restored" }
  } catch { $restoreFailures += $_ }
  foreach ($permission in $permissions) {
    try { Set-PermissionState $permission $user $states[$permission] } catch { $restoreFailures += $_ }
  }
}
if ($failure) { Write-Error -ErrorAction Continue "Test failure: $failure" }
foreach ($restoreFailure in $restoreFailures) { Write-Error -ErrorAction Continue "Restore failure: $restoreFailure" }
if ($failure -or $restoreFailures.Count) { exit 1 }
