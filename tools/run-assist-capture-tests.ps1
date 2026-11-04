param(
  [Parameter(Mandatory = $true)][ValidatePattern('^emulator-\d+$')][string]$Serial,
  [string]$OutputDirectory = (Join-Path $PSScriptRoot '../app/build/outputs/androidTest-results/assist-capture')
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$package = 'ru.outsideproarts.oassist'
$role = 'android.app.role.ASSISTANT'
$secureKeys = @('assistant', 'voice_interaction_service', 'voice_recognition_service', 'assist_structure_enabled', 'assist_screenshot_enabled')
$supportedPermissionFlags = @('USER_SET', 'USER_FIXED', 'USER_SENSITIVE_WHEN_GRANTED', 'USER_SENSITIVE_WHEN_DENIED')

function Invoke-Adb([string[]]$Arguments) {
  $output = @(& adb -s $Serial @Arguments 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output -join "`n")" }
  return ($output -join "`n")
}

function ConvertFrom-RoleDump([string]$Dump, [string]$UserId, [string]$RoleName) {
  # Accept the API 31 dumpsys structure only in full: partial matches could silently lose holders.
  $rolePattern = '\{\s*name=(?<name>[A-Za-z0-9_.]+)\s*(?:holders=(?<holder>[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)\s*)*\}'
  $userPattern = "user_id=(?<id>\d+)\s*version=-?\d+\s*packages_hash=[^\r\n]*\s*roles=\[\s*(?:(?<role>$rolePattern)\s*)*\]"
  $match = [regex]::Match($Dump, "\AROLE STATE \(dumpsys role\):\s*\{\s*user_states=\{\s*(?:(?<user>$userPattern)\s*)*\}\s*\}\s*\z")
  if (-not $match.Success) { throw 'Unparseable role state dump' }
  $users = [System.Collections.Generic.Dictionary[string, bool]]::new([StringComparer]::Ordinal)
  $result = $null
  foreach ($userBlock in $match.Groups['user'].Captures) {
    $parsedUser = [regex]::Match($userBlock.Value, "\A$userPattern\z")
    $id = $parsedUser.Groups['id'].Value
    if ($users.ContainsKey($id)) { throw "Duplicate role state for user $id" }
    $users[$id] = $true
    $roles = [System.Collections.Generic.Dictionary[string, string[]]]::new([StringComparer]::Ordinal)
    foreach ($roleBlock in $parsedUser.Groups['role'].Captures) {
      $parsedRole = [regex]::Match($roleBlock.Value, "\A$rolePattern\z")
      $name = $parsedRole.Groups['name'].Value
      if ($roles.ContainsKey($name)) { throw "Duplicate role $name for user $id" }
      $holders = @($parsedRole.Groups['holder'].Captures | ForEach-Object { $_.Value })
      if (@($holders | Sort-Object -Unique).Count -ne $holders.Count) { throw "Duplicate holder for role $name and user $id" }
      $roles[$name] = $holders
    }
    if ($id -ceq $UserId) {
      if (-not $roles.ContainsKey($RoleName)) { throw "Missing role $RoleName for user $UserId" }
      $result = @($roles[$RoleName] | Sort-Object)
    }
  }
  if (-not $users.ContainsKey($UserId)) { throw "Missing role state for user $UserId" }
  return $result
}

function Get-Roles {
  if (-not $roleGetSupported) {
    return @(ConvertFrom-RoleDump (Invoke-Adb @('shell', 'dumpsys', 'role')) $user $role)
  }
  return @((Invoke-Adb @('shell', 'cmd', 'role', 'get-role-holders', '--user', $user, $role)) -split '\r?\n' |
    ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object)
}

function Get-SecureState {
  $lines = (Invoke-Adb @('shell', 'settings', '--user', $user, 'list', 'secure')) -split '\r?\n'
  $state = [ordered]@{}
  foreach ($key in $secureKeys) {
    $entry = @($lines | Where-Object { $_.StartsWith("${key}=") })
    if ($entry.Count -gt 1) { throw "Duplicate secure setting $key" }
    $state[$key] = @{ Exists = $entry.Count -eq 1; Value = if ($entry.Count) { $entry[0].Substring($key.Length + 1) } else { $null } }
  }
  return $state
}

function Get-OverlayMode {
  $output = Invoke-Adb @('shell', 'appops', 'get', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW')
  if ($output -match 'SYSTEM_ALERT_WINDOW: (\w+)') { return $Matches[1] }
  if ($output -match 'No operations') { return 'default' }
  throw "Unknown overlay appop state: $output"
}

function Get-PermissionState([string]$Permission) {
  $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
  $section = [regex]::Match($dump, "(?ms)^\s*User ${user}:.*?(?=^\s*User \d+:|\z)")
  if (-not $section.Success) { throw "Missing package state for user $user" }
  $line = [regex]::Match($section.Value, "(?m)^\s*$([regex]::Escape($Permission)):\s*granted=(true|false),\s*flags=\[([^\]]*)\]")
  if (-not $line.Success) { throw "Missing runtime permission $Permission" }
  $flags = @($line.Groups[2].Value -split '\|' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object)
  return @{ Granted = $line.Groups[1].Value -eq 'true'; UserSet = 'USER_SET' -in $flags; UserFixed = 'USER_FIXED' -in $flags; Flags = ($flags -join '|') }
}

function Set-UserPermissionFlags([string]$Permission, [bool]$UserSet, [bool]$UserFixed) {
  if ([int]$sdkText -lt 33) {
    $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w',
      '-e', 'class', "$package.permission.PermissionFlagsFixture", '-e', 'permissionFlagsFixture', 'true',
      '-e', 'permission', $Permission, '-e', 'userSet', $UserSet.ToString().ToLowerInvariant(),
      '-e', 'userFixed', $UserFixed.ToString().ToLowerInvariant(), "$package.test/androidx.test.runner.AndroidJUnitRunner")
    if ($output -notmatch '(?m)^OK \(1 test\)\s*$' -or $output -match '(?i)FAILURES!!!|INSTRUMENTATION_FAILED|ignored|skipped') {
      throw "Could not update permission flags for ${Permission}: $output"
    }
    return
  }
  Invoke-Adb @('shell', 'pm', 'clear-permission-flags', '--user', $user, $package, $Permission, 'user-set', 'user-fixed') | Out-Null
  if ($UserSet) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $user, $package, $Permission, 'user-set') | Out-Null }
  if ($UserFixed) { Invoke-Adb @('shell', 'pm', 'set-permission-flags', '--user', $user, $package, $Permission, 'user-fixed') | Out-Null }
}

function Set-PermissionState([string]$Permission, [hashtable]$State) {
  Set-UserPermissionFlags $Permission $false $false
  $action = if ($State.Granted) { 'grant' } else { 'revoke' }
  Invoke-Adb @('shell', 'pm', $action, '--user', $user, $package, $Permission) | Out-Null
  if ($State.UserSet -or $State.UserFixed) { Set-UserPermissionFlags $Permission $State.UserSet $State.UserFixed }
}

function Get-FixtureState {
  $permissionState = [ordered]@{}
  foreach ($permission in $permissions) { $permissionState[$permission] = Get-PermissionState $permission }
  return @{ Roles = @(Get-Roles); Secure = Get-SecureState; Overlay = Get-OverlayMode; Permissions = $permissionState }
}

function Invoke-Tests([string[]]$Classes, [int]$Count, [string]$LogName) {
  Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
  $output = Invoke-Adb @('shell', 'am', 'instrument', '--user', $user, '-w', '-e', 'assistCaptureFixture', 'true',
    '-e', 'class', ($Classes -join ','), "$package.test/androidx.test.runner.AndroidJUnitRunner")
  $output | Set-Content -LiteralPath (Join-Path $OutputDirectory $LogName) -Encoding utf8
  Write-Output $output
  if ($output -notmatch "(?m)^OK \($Count tests?\)\s*$" -or $output -match '(?i)FAILURES!!!|INSTRUMENTATION_FAILED|ignored|skipped') {
    throw "Instrumentation did not report exactly $Count passing tests: $LogName"
  }
}

# Reject unsupported state before changing the emulator. Installation and AVD lifecycle belong to the caller.
if ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') { throw 'Only an emulator is supported' }
$user = (Invoke-Adb @('shell', 'am', 'get-current-user')).Trim()
if ($user -notmatch '^\d+$') { throw "Cannot identify current user: $user" }
$sdkText = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
if ($sdkText -notmatch '^\d+$' -or [int]$sdkText -lt 31) { throw "Unsupported Android API: $sdkText" }
# API 31 prints valid help with exit code 255. Accept that status only for this read-only probe.
$roleHelp = (@(& adb -s $Serial shell cmd role help 2>&1) -join "`n")
if ($LASTEXITCODE -notin @(0, 255) -or $roleHelp -notmatch '\ARole \(role\) commands:' -or
    $roleHelp -notmatch '(?m)^\s*add-role-holder\s' -or $roleHelp -notmatch '(?m)^\s*remove-role-holder\s') { throw 'Unrecognized role command help' }
$roleGetSupported = $roleHelp -match '(?m)^\s*get-role-holders\s'
foreach ($installed in @($package, "$package.test")) {
  if ((Invoke-Adb @('shell', 'pm', 'path', $installed)) -notmatch '(?m)^package:') { throw "Install $installed before running this script" }
}
$permissions = @('android.permission.RECORD_AUDIO')
if ([int]$sdkText -ge 33) { $permissions += 'android.permission.POST_NOTIFICATIONS' }
$before = Get-FixtureState
foreach ($holder in $before.Roles) {
  if ($holder -notmatch '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$') { throw "Unsupported assistant role holder: $holder" }
}
if ($before.Overlay -notin @('allow', 'deny', 'ignore', 'default')) { throw "Unsupported overlay mode: $($before.Overlay)" }
foreach ($key in $secureKeys) {
  if ($before.Secure[$key].Exists -and $before.Secure[$key].Value -notmatch '^[A-Za-z0-9_./:-]*$') { throw "Unsupported secure setting value: $key" }
}
foreach ($permission in $permissions) {
  $flags = @($before.Permissions[$permission].Flags -split '\|' | Where-Object { $_ })
  if (@($flags | Where-Object { $_ -notin $supportedPermissionFlags }).Count) { throw "Unsupported permission flags for ${permission}: $($flags -join '|')" }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'state-before.json') -Encoding utf8
$testFailure = $null
$restoreFailures = [System.Collections.Generic.List[string]]::new()
function Restore-Step([scriptblock]$Action) {
  try { & $Action } catch { $restoreFailures.Add($_.ToString()) }
}
try {
  Invoke-Adb @('shell', 'cmd', 'role', 'add-role-holder', '--user', $user, $role, $package) | Out-Null
  if ((@(Get-Roles) -join ',') -cne $package) { throw 'OAssist was not selected as assistant' }
  $selected = Get-SecureState
  if ($selected.voice_interaction_service.Value -cne "$package/.vis.OAssistVoiceInteractionService") { throw 'The installed APK did not register its VoiceInteractionService' }
  foreach ($key in @('assist_structure_enabled', 'assist_screenshot_enabled')) {
    Invoke-Adb @('shell', 'settings', '--user', $user, 'put', 'secure', $key, '1') | Out-Null
  }
  Invoke-Adb @('shell', 'appops', 'set', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW', 'deny') | Out-Null
  if ((Get-OverlayMode) -ne 'deny') { throw 'Could not disable overlay access for capture fixtures' }
  Invoke-Tests @("$package.vis.VoiceInteractionCaptureTest") 1 'framework.log'
  Invoke-Tests @("$package.vis.AssistHandoffLifecycleTest") 2 'handoff.log'
  foreach ($permission in $permissions) {
    Set-PermissionState $permission @{ Granted = $true; UserSet = $false; UserFixed = $false }
    if (-not (Get-PermissionState $permission).Granted) { throw "Could not grant fixture permission: $permission" }
  }
  Invoke-Tests @("$package.vis.AssistServiceHandoffTest", "$package.vis.AssistTurnSnapshotTest") 2 'service-turn.log'
} catch { $testFailure = $_ }
finally {
  Restore-Step {
    foreach ($holder in @(Get-Roles)) {
      if ($holder -notin $before.Roles) { Invoke-Adb @('shell', 'cmd', 'role', 'remove-role-holder', '--user', $user, $role, $holder) | Out-Null }
    }
    foreach ($holder in $before.Roles) {
      Invoke-Adb @('shell', 'cmd', 'role', 'add-role-holder', '--user', $user, $role, $holder) | Out-Null
    }
  }
  foreach ($key in $secureKeys) {
    Restore-Step {
      if ($before.Secure[$key].Exists) {
        # adb shell needs explicit quotes to preserve an empty value as a real argument.
        $value = if ($before.Secure[$key].Value -eq '') { "''" } else { $before.Secure[$key].Value }
        Invoke-Adb @('shell', 'settings', '--user', $user, 'put', 'secure', $key, $value) | Out-Null
      } else { Invoke-Adb @('shell', 'settings', '--user', $user, 'delete', 'secure', $key) | Out-Null }
    }
  }
  Restore-Step { Invoke-Adb @('shell', 'appops', 'set', '--user', $user, $package, 'SYSTEM_ALERT_WINDOW', $before.Overlay) | Out-Null }
  foreach ($permission in $permissions) { Restore-Step { Set-PermissionState $permission $before.Permissions[$permission] } }
  Restore-Step {
    $after = Get-FixtureState
    $after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'state-after.json') -Encoding utf8
    if (($after.Roles -join ',') -cne ($before.Roles -join ',')) { throw 'Assistant role was not restored' }
    if ($after.Overlay -cne $before.Overlay) { throw 'Overlay appop was not restored' }
    foreach ($key in $secureKeys) {
      if ($after.Secure[$key].Exists -ne $before.Secure[$key].Exists -or $after.Secure[$key].Value -cne $before.Secure[$key].Value) {
        throw "Secure setting was not restored: $key"
      }
    }
    foreach ($permission in $permissions) {
      foreach ($field in @('Granted', 'Flags')) {
        if ($after.Permissions[$permission][$field] -cne $before.Permissions[$permission][$field]) { throw "Permission was not restored: $permission $field" }
      }
    }
  }
}
if ($testFailure) { Write-Error -ErrorAction Continue "Test failure: $testFailure" }
foreach ($failure in $restoreFailures) { Write-Error -ErrorAction Continue "Restore failure: $failure" }
if ($testFailure -or $restoreFailures.Count) { exit 1 }
Write-Output 'Assist capture fixtures passed; role, settings, appop and permissions restored and verified.'
