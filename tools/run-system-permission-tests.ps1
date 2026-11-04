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

function Invoke-Adb([string[]]$Arguments) {
  $output = @(& adb -s $Serial @Arguments 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output -join "`n")" }
  return ($output -join "`n")
}

function Get-PermissionState([string]$Permission, [string]$User) {
  $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
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

function Set-PermissionState([string]$Permission, [string]$User, [hashtable]$State) {
  Invoke-Adb @('shell', 'pm', 'clear-permission-flags', '--user', $User, $package, $Permission, 'user-set', 'user-fixed') | Out-Null
  $action = if ($State.Granted) { 'grant' } else { 'revoke' }
  Invoke-Adb @('shell', 'pm', $action, '--user', $User, $package, $Permission) | Out-Null
  if ($State.UserSet) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $User, $package, $Permission, 'user-set') | Out-Null }
  if ($State.UserFixed) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $User, $package, $Permission, 'user-fixed') | Out-Null }
  $actual = Get-PermissionState $Permission $User
  foreach ($key in @('Granted', 'UserSet', 'UserFixed')) {
    if ($actual[$key] -ne $State[$key]) { throw "Failed to restore $Permission $key" }
  }
  if ($State.ContainsKey('Flags') -and $actual.Flags -ne $State.Flags) { throw "Failed to restore $Permission flags" }
}

if ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') { throw 'Only an emulator is supported' }
$user = (Invoke-Adb @('shell', 'am', 'get-current-user')).Trim()
if ($user -notmatch '^\d+$') { throw "Cannot identify current Android user: $user" }
$overlay = Invoke-Adb @('shell', 'appops', 'get', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW')
if ($overlay -notmatch 'SYSTEM_ALERT_WINDOW: (deny|ignore|default)' -and $overlay -notmatch 'No operations') {
  throw "Overlay must be disabled: $overlay"
}
$states = @{}
foreach ($permission in $permissions) { $states[$permission] = Get-PermissionState $permission $user }
$failure = $null
$restoreFailures = @()
try {
  foreach ($permission in $permissions) { Set-PermissionState $permission $user @{ Granted = $false; UserSet = $false; UserFixed = $false } }
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'systemPermissionFixture', 'true', '-e', 'class', "$testClass,$chatTestClass", "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(6 tests\)\s*$') { throw 'Instrumentation did not report six passing tests' }
  Set-PermissionState 'android.permission.RECORD_AUDIO' $user @{ Granted = $false; UserSet = $false; UserFixed = $false }
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'systemPermissionFixture', 'true', '-e', 'class', $holdTestClass, "$package.test/androidx.test.runner.AndroidJUnitRunner")
  Write-Output $output
  if ($output -notmatch '(?m)^OK \(1 test\)\s*$') { throw 'Hold permission instrumentation did not report one passing test' }
} catch { $failure = $_ }
finally {
  foreach ($permission in $permissions) {
    try { Set-PermissionState $permission $user $states[$permission] } catch { $restoreFailures += $_ }
  }
}
if ($failure) { Write-Error -ErrorAction Continue "Test failure: $failure" }
foreach ($restoreFailure in $restoreFailures) { Write-Error -ErrorAction Continue "Restore failure: $restoreFailure" }
if ($failure -or $restoreFailures.Count) { exit 1 }
