[CmdletBinding(DefaultParameterSetName = 'Path')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Path')][string]$ApkPath,
    [Parameter(Mandatory, ParameterSetName = 'Directory')][string]$ApkDirectory,
    [Parameter(Mandatory)][string]$Tag,
    [Parameter(Mandatory)][bool]$Prerelease,
    [Parameter(Mandatory)][int]$ExpectedVersionCode
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Import-Module (Join-Path $PSScriptRoot 'OAssistCi.psm1') -Force
$metadata = Get-OAssistCiMetadata -Ref "refs/tags/$Tag" -RunNumber ([string]$ExpectedVersionCode) -VersionCodeBase '0'
if ($metadata.Prerelease -ne $Prerelease) { throw 'Prerelease flag does not match the tag.' }
if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) { throw 'GH_TOKEN is required.' }
$repository = $env:GITHUB_REPOSITORY
if ($repository -cnotmatch '\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\z') { throw 'Invalid GITHUB_REPOSITORY.' }
$expectedCommit = $env:GITHUB_SHA
if ($expectedCommit -cnotmatch '\A[0-9a-fA-F]{40}\z') { throw 'Invalid GITHUB_SHA.' }
if ($PSCmdlet.ParameterSetName -eq 'Directory') {
    $apks = @(Get-ChildItem -LiteralPath $ApkDirectory -Filter '*.apk' -File)
    if ($apks.Count -ne 1) { throw 'Expected exactly one downloaded APK.' }
    $ApkPath = $apks[0].FullName
}
$apk = [IO.Path]::GetFullPath($ApkPath)
$checksum = $apk + '.sha256'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf) -or -not (Test-Path -LiteralPath $checksum -PathType Leaf)) { throw 'APK or checksum file is missing.' }
$filename = [IO.Path]::GetFileName($apk)
if ($filename -match '[\r\n]') { throw 'Invalid APK filename.' }
$checksumText = [IO.File]::ReadAllText($checksum)
$checksumMatch = [regex]::Match($checksumText, '\A([0-9a-fA-F]{64})  ([^\r\n]+)\r?\n?\z')
if (-not $checksumMatch.Success -or $checksumMatch.Groups[2].Value -cne $filename -or
    $checksumMatch.Groups[1].Value -ine (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash) { throw 'APK checksum does not match.' }

function Invoke-GhChecked {
    param([string[]]$Arguments)
    $output = (& gh @Arguments 2>&1 | Out-String)
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $safeOutput = $output.Replace($env:GH_TOKEN, '[redacted]')
        if ($safeOutput.Length -gt 3000) { $safeOutput = $safeOutput.Substring(0, 3000) + '...' }
        throw "gh failed (exit $code): $($safeOutput.Trim())"
    }
    return $output
}

$refJson = Invoke-GhChecked -Arguments @('api', "repos/$repository/git/ref/tags/$Tag")
$object = ($refJson | ConvertFrom-Json).object
$visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
for ($depth = 0; $object.type -ceq 'tag'; $depth++) {
    if ($depth -ge 10 -or $object.sha -cnotmatch '\A[0-9a-fA-F]{40}\z' -or -not $visited.Add([string]$object.sha)) { throw 'Invalid or cyclic annotated tag.' }
    $tagJson = Invoke-GhChecked -Arguments @('api', "repos/$repository/git/tags/$($object.sha)")
    $object = ($tagJson | ConvertFrom-Json).object
}
if ($object.type -cne 'commit' -or $object.sha -cnotmatch '\A[0-9a-fA-F]{40}\z' -or $object.sha -ine $expectedCommit) { throw 'Remote tag does not resolve to GITHUB_SHA.' }

$releaseResponse = Invoke-GhChecked -Arguments @('api', '--paginate', '--slurp', "repos/$repository/releases?per_page=100")
$pages = ConvertFrom-Json -InputObject $releaseResponse -NoEnumerate
$matchingReleases = @($pages | ForEach-Object { $_ } | ForEach-Object { $_ } | Where-Object { $_.tag_name -ceq $Tag })
if ($matchingReleases.Count -gt 1) { throw 'Multiple releases use the same tag.' }
if ($matchingReleases.Count -eq 1) {
    $release = $matchingReleases[0]
    if ($release.draft -cne $true) { throw 'Release is already published; refusing to overwrite assets.' }
    if ($release.prerelease -cne $Prerelease) { throw 'Existing draft prerelease flag does not match the tag.' }
    Invoke-GhChecked -Arguments @('release', 'upload', $Tag, $apk, $checksum, '--clobber', '--repo', $repository) | Out-Null
} else {
    $tempRoot = if ($env:RUNNER_TEMP) { [IO.Path]::GetFullPath($env:RUNNER_TEMP) } else { [IO.Path]::GetTempPath() }
    if (-not (Test-Path -LiteralPath $tempRoot -PathType Container)) { throw 'Temporary directory does not exist.' }
    $notes = Join-Path $tempRoot ('oassist-release-notes-' + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        [IO.File]::WriteAllText($notes, "Release $Tag`n", [Text.UTF8Encoding]::new($false))
        $arguments = @('release', 'create', $Tag, $apk, $checksum, '--verify-tag', '--draft', '--notes-file', $notes, '--title', $Tag, '--repo', $repository)
        if ($Prerelease) { $arguments += '--prerelease' }
        Invoke-GhChecked -Arguments $arguments | Out-Null
    } finally {
        if (Test-Path -LiteralPath $notes -PathType Leaf) { Remove-Item -LiteralPath $notes -Force -ErrorAction Stop }
    }
}
Invoke-GhChecked -Arguments @('release', 'edit', $Tag, '--draft=false', '--repo', $repository) | Out-Null
Write-Output "Published release $Tag."
