[CmdletBinding()]
param([Parameter(Mandatory)][string]$ApkDirectory, [Parameter(Mandatory)][long]$ExpectedVersionCode)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = $env:GITHUB_REPOSITORY
$commit = $env:GITHUB_SHA
if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN) -or $repository -cnotmatch '\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\z' -or $commit -cnotmatch '\A[0-9a-f]{40}\z') { throw 'Invalid GitHub environment.' }
$apks = @(Get-ChildItem -LiteralPath $ApkDirectory -File -Filter '*.apk')
if ($apks.Count -ne 1) { throw 'Expected exactly one APK.' }
$apk = $apks[0]
$checksum = $apk.FullName + '.sha256'
$manifestPath = Join-Path $apk.DirectoryName "update-$ExpectedVersionCode.json"
if (-not (Test-Path -LiteralPath $checksum -PathType Leaf) -or -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Missing update asset.' }
$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ([IO.File]::ReadAllText($checksum) -cnotmatch ("\A$hash  " + [regex]::Escape($apk.Name) + "\r?\n?\z")) { throw 'Invalid checksum.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.versionCode -ne $ExpectedVersionCode -or $manifest.apkName -cne $apk.Name -or $manifest.apkSha256 -cne $hash -or $manifest.apkSize -ne $apk.Length -or $manifest.commitSha -cne $commit -or $manifest.sourceRef -cne 'refs/heads/master' -or $manifest.channel -cne 'development') { throw 'Update manifest does not match APK or build.' }
function Invoke-GhChecked([string[]]$Arguments) {
    $output = (& gh @Arguments 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "gh failed (exit $LASTEXITCODE): $($output.Replace($env:GH_TOKEN, '[redacted]').Trim())" }
    return $output
}
function Test-CurrentMaster {
    $ref = Invoke-GhChecked @('api', "repos/$repository/git/ref/heads/master") | ConvertFrom-Json
    return $ref.object.type -ceq 'commit' -and $ref.object.sha -ceq $commit
}
if (-not (Test-CurrentMaster)) { Write-Output 'Skipping stale master build.'; return }
$pages = Invoke-GhChecked @('api', '--paginate', '--slurp', "repos/$repository/releases?per_page=100") | ConvertFrom-Json -NoEnumerate
$matches = @($pages | ForEach-Object { $_ } | ForEach-Object { $_ } | Where-Object { $_.tag_name -ceq 'updates-development' })
if ($matches.Count -gt 1) { throw 'Multiple development releases.' }
if ($matches.Count -eq 0) {
    Invoke-GhChecked @('release', 'create', 'updates-development', '--target', $commit, '--prerelease', '--latest=false', '--title', 'Development updates', '--notes', 'Rolling development builds.', '--repo', $repository) | Out-Null
    $assets = @()
} else {
    if ($matches[0].draft -or -not $matches[0].prerelease) { throw 'Development release must be a public prerelease.' }
    $releaseId = [long]$matches[0].id
    if ($releaseId -le 0) { throw 'Invalid development release ID.' }
    # The embedded release assets list can be incomplete; list every page explicitly.
    $assetPages = Invoke-GhChecked @('api', '--paginate', '--slurp', "repos/$repository/releases/$releaseId/assets?per_page=100") | ConvertFrom-Json -NoEnumerate
    $assets = @($assetPages | ForEach-Object { $_ } | ForEach-Object { $_ })
}
$paths = @($apk.FullName, $checksum, $manifestPath)
$missing = 0
foreach ($path in $paths) {
    $name = [IO.Path]::GetFileName($path)
    $existing = @($assets | Where-Object { $_.name -ceq $name })
    if ($existing.Count -gt 1) { throw 'Duplicate asset name.' }
    if ($existing.Count -eq 0) { $missing++; continue }
    $digest = 'sha256:' + (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existing[0].digest -cne $digest) { throw "Existing asset differs or has no digest: $name" }
}
if ($assets.Count -ge 900) {
    # These names are reserved for this workflow; names alone do not prove origin.
    # Treat duplicate names or differing APK suffixes as ambiguous and leave them alone.
    $groups = @{}
    foreach ($item in $assets) {
        $kind = $null; $stem = $null
        if ($item.name -cmatch '\Aupdate-([1-9][0-9]*)\.json\z') {
            $codeText = $Matches[1]; $kind = 'Manifest'
        } elseif ($item.name -cmatch '\A(oassist-debug-([1-9][0-9]*)-[0-9a-f]{7}\.apk)(\.sha256)?\z') {
            $stem = $Matches[1]; $codeText = $Matches[2]
            $kind = if ($Matches[3]) { 'Checksum' } else { 'Apk' }
        } else { continue }
        $code = [long]0
        if (-not [long]::TryParse($codeText, [ref]$code) -or $code -ge $ExpectedVersionCode) { continue }
        if (-not $groups.ContainsKey($code)) { $groups[$code] = @() }
        $groups[$code] += [pscustomobject]@{ Kind = $kind; Stem = $stem; Asset = $item }
    }
    $complete = @(); $orphans = @()
    foreach ($code in $groups.Keys) {
        $entries = @($groups[$code])
        $manifests = @($entries | Where-Object Kind -CEQ 'Manifest')
        $apks = @($entries | Where-Object Kind -CEQ 'Apk')
        $sums = @($entries | Where-Object Kind -CEQ 'Checksum')
        if ($manifests.Count -gt 1 -or $apks.Count -gt 1 -or $sums.Count -gt 1) { continue }
        if ($apks.Count -eq 1 -and $sums.Count -eq 1 -and $apks[0].Stem -cne $sums[0].Stem) { continue }
        if ($manifests.Count -eq 1 -and $apks.Count -eq 1 -and $sums.Count -eq 1) {
            $complete += [pscustomobject]@{ Code = $code; Assets = @($manifests[0].Asset, $apks[0].Asset, $sums[0].Asset) }
        } elseif ($manifests.Count -eq 0 -and ($apks.Count + $sums.Count) -gt 0) {
            # A previous run may have deleted the manifest, then failed mid-cleanup.
            $orphans += [pscustomobject]@{ Code = $code; Assets = @($apks | ForEach-Object Asset) + @($sums | ForEach-Object Asset) }
        }
    }
    $ordered = @($complete | Sort-Object Code -Descending)
    $remove = @($ordered | Select-Object -Skip 200)
    if ($ordered.Count -ge 200) {
        $oldestRetained = $ordered[199].Code
        $remove += @($orphans | Where-Object { $_.Code -lt $oldestRetained })
    }
    $removeCount = 0
    foreach ($set in $remove) {
        foreach ($asset in $set.Assets) {
            if ([long]$asset.id -le 0) { throw 'Invalid asset ID.' }
            $removeCount++
        }
    }
    if (($assets.Count - $removeCount + $missing) -gt 1000) { throw 'Development release is full; incomplete or unknown assets require manual inspection.' }
    if ($removeCount -gt 0 -and -not (Test-CurrentMaster)) { Write-Output 'Skipping cleanup for stale master build.'; return }
    foreach ($set in $remove) {
        # Withdraw metadata first so new clients cannot select a removed APK.
        foreach ($asset in $set.Assets) {
            Invoke-GhChecked @('api', '--method', 'DELETE', "repos/$repository/releases/assets/$($asset.id)") | Out-Null
        }
    }
}
if (($assets.Count -lt 900) -and ($assets.Count + $missing -gt 1000)) { throw 'Development release is full.' }
foreach ($path in @($apk.FullName, $checksum, $manifestPath)) {
    if ($path -ceq $manifestPath -and -not (Test-CurrentMaster)) { Write-Output 'Skipping manifest for stale master build.'; return }
    $name = [IO.Path]::GetFileName($path)
    $existing = @($assets | Where-Object { $_.name -ceq $name })
    if ($existing.Count -gt 1) { throw 'Duplicate asset name.' }
    if ($existing.Count -eq 1) {
        $digest = 'sha256:' + (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existing[0].digest -cne $digest) { throw "Existing asset differs or has no digest: $name" }
        continue
    }
    Invoke-GhChecked @('release', 'upload', 'updates-development', $path, '--repo', $repository) | Out-Null
}
Write-Output 'Published development update.'
