$ErrorActionPreference = 'Stop'
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$global:commands = [Collections.Generic.List[object]]::new()
$global:scenario = 'new'
$global:masterChecks = 0
function global:gh {
    param([Parameter(ValueFromRemainingArguments)][string[]]$GhArguments)
    $global:commands.Add(@($GhArguments)); $global:LASTEXITCODE = 0
    if ($GhArguments[0] -eq 'api' -and $GhArguments[-1] -like '*/git/ref/heads/master') {
        $global:masterChecks++
        $sha = if ($global:scenario -eq 'stale' -or ($global:scenario -eq 'delayed' -and $global:masterChecks -gt 1)) { 'b' * 40 } else { 'a' * 40 }
        return "{`"object`":{`"type`":`"commit`",`"sha`":`"$sha`"}}"
    }
    if ($GhArguments[0] -eq 'api' -and $GhArguments[1] -eq '--method') {
        $id = [long](($GhArguments[-1] -split '/')[-1])
        if ($global:scenario -eq 'interrupted' -and $id -eq $global:failId) { $global:failId = 0; $global:LASTEXITCODE = 1; return 'simulated failure' }
        if ($global:scenario -eq 'interrupted') { $global:remoteAssets = @($global:remoteAssets | Where-Object { $_.id -ne $id }) }
        return
    }
    if ($GhArguments[0] -eq 'api' -and $GhArguments[-1] -like '*/releases/7/assets?per_page=100') {
        if ($global:scenario -eq 'interrupted') { return ConvertTo-Json -InputObject @(,$global:remoteAssets) -Depth 5 -Compress }
        if ($global:scenario -eq 'collision') { return '[[{"name":"sample.apk","digest":"sha256:bad"}]]' }
        if ($global:scenario -in @('retention','full')) {
            $assets = @()
            if ($global:scenario -eq 'retention') {
                foreach ($code in 1..301) {
                    $name = "oassist-debug-$code-abcdef0.apk"
                    $assets += @{ id = $code * 3; name = "update-$code.json" }, @{ id = $code * 3 + 1; name = $name }, @{ id = $code * 3 + 2; name = "$name.sha256" }
                }
                $assets += @{ id = 2001; name = 'unrelated' }, @{ id = 2002; name = 'update-4000.json' }
            } else { foreach ($i in 1..999) { $assets += @{ id = $i; name = "unrelated-$i" } } }
            return ConvertTo-Json -InputObject @(,$assets) -Depth 5 -Compress
        }
        return '[[]]'
    }
    if ($GhArguments[0] -eq 'api') {
        if ($global:scenario -in @('collision','retention','full','interrupted')) { return '[[{"id":7,"tag_name":"updates-development","draft":false,"prerelease":true}]]' }
        return '[[]]'
    }
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ('oassist-updates-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp | Out-Null
$names = @('GH_TOKEN','GITHUB_REPOSITORY','GITHUB_SHA'); $original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:GH_TOKEN = 'test'; $env:GITHUB_REPOSITORY = 'owner/repo'; $env:GITHUB_SHA = 'a' * 40
    $apk = Join-Path $temp 'sample.apk'; [IO.File]::WriteAllText($apk, 'sample')
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($apk + '.sha256'), "$hash  sample.apk`n")
    @{ versionCode = 5000; apkName = 'sample.apk'; apkSha256 = $hash; apkSize = 6; commitSha = $env:GITHUB_SHA; sourceRef = 'refs/heads/master'; channel = 'development' } | ConvertTo-Json | Set-Content (Join-Path $temp 'update-5000.json')
    $script = Join-Path $PSScriptRoot 'Publish-OAssistDevelopment.ps1'
    & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null
    $writes = @($global:commands | Where-Object { $_[0] -eq 'release' })
    Assert-True ($writes.Count -eq 4 -and $writes[0] -contains '--latest=false' -and $writes[-1][3] -like '*update-5000.json') 'Manifest must be published last.'
    foreach ($case in @('stale','delayed','collision')) {
        $global:scenario = $case; $global:masterChecks = 0; $global:commands.Clear()
        $failed = $false; try { & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null } catch { $failed = $true }
        $writes = @($global:commands | Where-Object { $_[0] -eq 'release' })
        if ($case -eq 'stale') { Assert-True ($writes.Count -eq 0) 'Stale build writes nothing.' }
        if ($case -eq 'delayed') { Assert-True (@($writes | Where-Object { $_ -like '*update-5000.json' }).Count -eq 0) 'Stale build does not publish manifest.' }
        if ($case -eq 'collision') { Assert-True ($failed -and $writes.Count -eq 0) 'Conflicting immutable asset is rejected.' }
    }
    $global:scenario = 'retention'; $global:masterChecks = 0; $global:commands.Clear()
    & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null
    $deletes = @($global:commands | Where-Object { $_[0] -eq 'api' -and $_[1] -eq '--method' })
    Assert-True ($deletes.Count -eq 303) 'Retain the newest 200 complete sets and leave partial and unknown assets.'
    Assert-True (@($deletes | Where-Object { $_[-1] -match '/(2001|2002)$' }).Count -eq 0) 'Partial and unknown assets remain.'
    Assert-True ($deletes[0][-1] -eq 'repos/owner/repo/releases/assets/303' -and $deletes[1][-1] -eq 'repos/owner/repo/releases/assets/304' -and $deletes[2][-1] -eq 'repos/owner/repo/releases/assets/305') 'Delete each manifest before its APK and checksum.'
    foreach ($failureId in @(304, 305)) {
        $global:remoteAssets = @()
        foreach ($code in 1..301) {
            $name = "oassist-debug-$code-abcdef0.apk"
            $global:remoteAssets += @{ id = $code * 3; name = "update-$code.json" }, @{ id = $code * 3 + 1; name = $name }, @{ id = $code * 3 + 2; name = "$name.sha256" }
        }
        # Ambiguous old sets and recent incomplete sets must survive recovery.
        $global:remoteAssets += @{ id = 2001; name = 'oassist-debug-1-bbbbbbb.apk' }, @{ id = 2002; name = 'oassist-debug-4999-abcdef0.apk' }, @{ id = 2003; name = 'unrelated' }
        $global:scenario = 'interrupted'; $global:failId = $failureId; $global:masterChecks = 0; $global:commands.Clear(); $failed = $false
        try { & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null } catch { $failed = $true }
        Assert-True ($failed -and @($global:remoteAssets | Where-Object id -EQ 303).Count -eq 0) 'Simulate failure after manifest deletion.'
        $global:commands.Clear(); $global:masterChecks = 0
        & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null
        Assert-True (@($global:remoteAssets | Where-Object { $_.id -in @(304,305) }).Count -eq 0) 'Retry reclaims remaining old assets.'
        Assert-True (@($global:remoteAssets | Where-Object { $_.id -in @(3,4,5,2001,2002,2003) }).Count -eq 6) 'Retry retains ambiguous, recent incomplete and unknown assets.'
    }
    $global:scenario = 'full'; $global:masterChecks = 0; $global:commands.Clear(); $failed = $false
    try { & $script -ApkDirectory $temp -ExpectedVersionCode 5000 | Out-Null } catch { $failed = $true }
    Assert-True ($failed -and @($global:commands | Where-Object { $_[0] -eq 'release' -or $_[1] -eq '--method' }).Count -eq 0) 'Fail without deleting unknown assets when full.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $original[$name]) }
    Remove-Item function:global:gh -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temp -Recurse -Force
}
Write-Host 'OAssist update publication tests passed.'
