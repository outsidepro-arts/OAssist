$ErrorActionPreference = 'Stop'
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    if (-not $threw) { throw "Expected failure: $Message" }
}

$global:oassistTestCommands = [Collections.Generic.List[object]]::new()
$global:oassistTestScenario = ''
$global:oassistTestCommit = 'a' * 40
function global:gh {
    param([Parameter(ValueFromRemainingArguments)][string[]]$GhArguments)
    $global:oassistTestCommands.Add(@($GhArguments))
    $global:LASTEXITCODE = 0
    if ($GhArguments[0] -eq 'api' -and $GhArguments[-1] -match '/git/ref/tags/') {
        if ($global:oassistTestScenario -eq 'mismatch') { return '{"object":{"type":"commit","sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}' }
        if ($global:oassistTestScenario -eq 'annotated') { return '{"object":{"type":"tag","sha":"cccccccccccccccccccccccccccccccccccccccc"}}' }
        return "{`"object`":{`"type`":`"commit`",`"sha`":`"$global:oassistTestCommit`"}}"
    }
    if ($GhArguments[0] -eq 'api' -and $GhArguments[-1] -match '/git/tags/') { return "{`"object`":{`"type`":`"commit`",`"sha`":`"$global:oassistTestCommit`"}}" }
    if ($GhArguments[0] -eq 'api' -and $GhArguments[-1] -match '/releases\?') {
        if ($global:oassistTestScenario -in @('draft', 'upload-failure')) { return '[[{"tag_name":"v1.2.3","draft":true,"prerelease":false}]]' }
        if ($global:oassistTestScenario -eq 'published') { return '[[{"tag_name":"v1.2.3","draft":false,"prerelease":false}]]' }
        if ($global:oassistTestScenario -eq 'api-failure') { $global:LASTEXITCODE = 1; return 'Permission denied' }
        return '[[]]'
    }
    if (($global:oassistTestScenario -eq 'upload-failure' -and $GhArguments[1] -eq 'upload') -or ($global:oassistTestScenario -eq 'create-failure' -and $GhArguments[1] -eq 'create')) { $global:LASTEXITCODE = 1; return 'simulated failure' }
}

$tempRoot = if ($env:TMPDIR) { $env:TMPDIR } else { [IO.Path]::GetTempPath() }
$temp = Join-Path $tempRoot ('oassist-release-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$names = @('GH_TOKEN', 'GITHUB_REPOSITORY', 'GITHUB_SHA', 'RUNNER_TEMP')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:GH_TOKEN = 'test-token'; $env:GITHUB_REPOSITORY = 'owner/repo'; $env:GITHUB_SHA = $global:oassistTestCommit; $env:RUNNER_TEMP = $temp
    $apk = Join-Path $temp 'sample.apk'; [IO.File]::WriteAllText($apk, 'sample')
    $checksum = $apk + '.sha256'
    $digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($checksum, "$digest  sample.apk`n")
    $helper = Join-Path $PSScriptRoot 'Publish-OAssistRelease.ps1'
    $params = @{ ApkPath = $apk; Tag = 'v1.2.3'; Prerelease = $false; ExpectedVersionCode = 3 }
    foreach ($scenario in @('mismatch', 'published', 'api-failure', 'upload-failure', 'create-failure')) {
        $global:oassistTestScenario = $scenario; $global:oassistTestCommands.Clear()
        Assert-Throws { & $helper @params } $scenario
        Assert-True (@($global:oassistTestCommands | Where-Object { $_[0] -eq 'release' -and $_[1] -eq 'edit' }).Count -eq 0) "No publish after $scenario"
        if ($scenario -in @('mismatch', 'published', 'api-failure')) { Assert-True (@($global:oassistTestCommands | Where-Object { $_[0] -eq 'release' }).Count -eq 0) "No release writes after $scenario" }
    }
    $global:oassistTestCommands.Clear(); [IO.File]::WriteAllText($checksum, ('0' * 64) + "  sample.apk`n")
    Assert-Throws { & $helper @params } 'Checksum mismatch'
    Assert-True ($global:oassistTestCommands.Count -eq 0) 'Checksum is checked before remote calls'
    [IO.File]::WriteAllText($checksum, "$digest  sample.apk`n")
    $global:oassistTestScenario = 'annotated'; $global:oassistTestCommands.Clear(); & $helper @params | Out-Null
    $writes = @($global:oassistTestCommands | Where-Object { $_[0] -eq 'release' })
    Assert-True ($writes.Count -eq 2 -and $writes[0][1] -eq 'create' -and $writes[1][1] -eq 'edit') 'Create draft then publish'
    Assert-True ($writes[0] -contains '--draft' -and $writes[0] -contains '--verify-tag' -and $writes[1] -contains '--draft=false' -and $writes[0] -notcontains '--prerelease') 'Stable draft options'
    Assert-True (@($global:oassistTestCommands | Where-Object { $_[0] -eq 'api' -and $_[-1] -match '/git/tags/' }).Count -eq 1) 'Annotated tag peeled'
    $global:oassistTestScenario = 'draft'; $global:oassistTestCommands.Clear(); & $helper @params | Out-Null
    $writes = @($global:oassistTestCommands | Where-Object { $_[0] -eq 'release' })
    Assert-True ($writes.Count -eq 2 -and $writes[0][1] -eq 'upload' -and $writes[0] -contains '--clobber' -and $writes[1][1] -eq 'edit') 'Resume draft then publish'
    $global:oassistTestScenario = 'new'; $global:oassistTestCommands.Clear(); $params.Tag = 'v1.2.3-rc.1'; $params.Prerelease = $true
    & $helper @params | Out-Null
    $create = @($global:oassistTestCommands | Where-Object { $_[0] -eq 'release' -and $_[1] -eq 'create' })[0]
    Assert-True ($create -contains '--prerelease') 'Prerelease flag'
    $global:oassistTestCommands.Clear(); $params.Prerelease = $false
    Assert-Throws { & $helper @params } 'Mismatched prerelease flag'
    Assert-True ($global:oassistTestCommands.Count -eq 0) 'Invalid metadata checked before remote calls'
    Assert-True (@(Get-ChildItem -LiteralPath $temp -Filter 'oassist-release-notes-*').Count -eq 0) 'Notes removed'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $original[$name]) }
    Remove-Item Function:\gh -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temp -Recurse -Force
}
Write-Host 'OAssist release publication tests passed.'
