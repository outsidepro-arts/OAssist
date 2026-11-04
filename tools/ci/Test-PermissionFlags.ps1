$ErrorActionPreference = 'Stop'

foreach ($runner in @('run-assist-capture-tests.ps1', 'run-system-permission-tests.ps1')) {
  & {
    param($runner)
    $tokens = $null; $errors = $null
    $path = Join-Path $PSScriptRoot "../$runner"
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "Cannot parse $runner" }
    foreach ($name in @('Set-UserPermissionFlags', 'Set-PermissionState')) {
      $definitions = @($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $false))
      if ($definitions.Count -ne 1) { throw "Cannot find $name in $runner" }
      Invoke-Expression $definitions[0].Extent.Text
    }
    $package = 'ru.outsideproarts.oassist'
    $user = '10'
    $permission = 'android.permission.RECORD_AUDIO'
    $system = $runner -eq 'run-system-permission-tests.ps1'
    $calls = [System.Collections.Generic.List[string]]::new()
    $fixtureOutput = 'OK (1 test)'
    function Invoke-Adb([string[]]$Arguments) {
      $calls.Add(($Arguments -join ' '))
      if ($Arguments[1] -eq 'am') { return $fixtureOutput }
      return ''
    }
    function Get-PermissionState { return $actualState }
    function Assert-Equal($Actual, $Expected, [string]$Message) {
      if ($Actual -cne $Expected) { throw "$runner : $Message; got '$Actual'" }
    }
    function Assert-Throws([scriptblock]$Action, [string]$Message) {
      try { & $Action | Out-Null } catch { return }
      throw "$runner : expected failure: $Message"
    }
    function Set-Flags([bool]$Set, [bool]$Fixed) {
      if ($system) { Set-UserPermissionFlags $permission $user $Set $Fixed $package }
      else { Set-UserPermissionFlags $permission $Set $Fixed }
    }
    function Set-State([hashtable]$State) {
      if ($system) { Set-PermissionState $permission $user $State $package }
      else { Set-PermissionState $permission $State }
    }
    $fixturePrefix = "shell am instrument --user 10 -w -e class $package.permission.PermissionFlagsFixture -e permissionFlagsFixture true -e permission $permission"
    $fixtureSuffix = "$package.test/androidx.test.runner.AndroidJUnitRunner"
    foreach ($version in @(31, 32)) {
      $sdk = $version; $sdkText = "$version"; $calls.Clear()
      Set-Flags $true $false
      Assert-Equal ($calls -join '|') "$fixturePrefix -e userSet true -e userFixed false $fixtureSuffix" "API $version fixture arguments"
    }
    foreach ($bad in @('OK (2 tests)', 'FAILURES!!!', "OK (1 test)`nINSTRUMENTATION_FAILED", "OK (1 test)`nignored", "OK (1 test)`nskipped", '')) {
      $fixtureOutput = $bad; $calls.Clear()
      Assert-Throws { Set-Flags $false $true } "invalid instrumentation output: $bad"
    }
    if ($system) {
      $calls.Clear(); $fixtureOutput = 'OK (1 test)'
      Assert-Throws { Set-UserPermissionFlags $permission $user $true $false "$package.test" } 'unsupported target on API 32'
      Assert-Equal $calls.Count 0 'target guard must precede adb'
    }
    $sdk = 33; $sdkText = '33'; $calls.Clear()
    Set-Flags $true $true
    Assert-Equal ($calls -join '|') "shell pm clear-permission-flags --user 10 $package $permission user-set user-fixed|shell pm set-permission-flags --user 10 $package $permission user-set|shell pm set-permission-flags --user 10 $package $permission user-fixed" 'modern flag commands'
    $calls.Clear(); Set-Flags $false $false
    Assert-Equal ($calls -join '|') "shell pm clear-permission-flags --user 10 $package $permission user-set user-fixed" 'modern clear-only commands'
    if ($system) {
      $calls.Clear(); Set-UserPermissionFlags $permission $user $true $false "$package.test"
      Assert-Equal ($calls -join '|') "shell pm clear-permission-flags --user 10 $package.test $permission user-set user-fixed|shell pm set-permission-flags --user 10 $package.test $permission user-set" 'modern target package'
    }
    $sdk = 31; $sdkText = '31'; $fixtureOutput = 'OK (1 test)'; $calls.Clear()
    $expectedState = @{ Granted = $false; UserSet = $false; UserFixed = $true }
    $actualState = $expectedState
    Set-State $expectedState
    Assert-Equal ($calls -join '|') "$fixturePrefix -e userSet false -e userFixed false $fixtureSuffix|shell pm revoke --user 10 $package $permission|$fixturePrefix -e userSet false -e userFixed true $fixtureSuffix" 'clear, revoke, restore order'
    $fixtureOutput = 'FAILURES!!!'; $calls.Clear()
    Assert-Throws { Set-State $expectedState } 'clear failure stops operation'
    Assert-Equal $calls.Count 1 'failed clear must prevent permission mutation'
    $sdk = 33; $sdkText = '33'; $calls.Clear()
    $expectedState = @{ Granted = $true; UserSet = $true; UserFixed = $false }
    $actualState = $expectedState
    Set-State $expectedState
    Assert-Equal ($calls -join '|') "shell pm clear-permission-flags --user 10 $package $permission user-set user-fixed|shell pm grant --user 10 $package $permission|shell pm clear-permission-flags --user 10 $package $permission user-set user-fixed|shell pm set-permission-flags --user 10 $package $permission user-set" 'modern clear, grant, restore order'
    if ($system) {
      $actualState = @{ Granted = $true; UserSet = $false; UserFixed = $false }
      Assert-Throws { Set-State $expectedState } 'restored flags must be verified'
    }
  } $runner
}
Write-Output 'Permission flag wrapper tests passed.'
