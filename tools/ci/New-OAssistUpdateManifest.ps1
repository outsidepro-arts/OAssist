[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$BuildToolsDirectory,
    [Parameter(Mandatory)][string]$CommitSha,
    [Parameter(Mandatory)][string]$SourceRef,
    [Parameter(Mandatory)][ValidateSet('stable','prerelease','development')][string]$Channel,
    [Parameter(Mandatory)][string]$CertificateSha256
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($CommitSha -cnotmatch '\A[0-9a-f]{40}\z' -or $CertificateSha256 -cnotmatch '\A[0-9a-f]{64}\z') { throw 'Invalid commit or certificate digest.' }
if (($Channel -eq 'development' -and $SourceRef -cne 'refs/heads/master') -or ($Channel -ne 'development' -and $SourceRef -cnotmatch '\Arefs/tags/v[0-9A-Za-z.+-]+\z')) { throw 'Invalid source ref.' }
$apk = Get-Item -LiteralPath $ApkPath
if ($apk.Name -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]*\.apk\z') { throw 'Invalid APK filename.' }
$aapt = Join-Path $BuildToolsDirectory $(if ($IsWindows) { 'aapt2.exe' } else { 'aapt2' })
$badging = (& $aapt dump badging $apk.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
$package = [regex]::Match($badging, "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'")
$sdk = [regex]::Match($badging, "(?m)^minSdkVersion:'([0-9]+)'")
if (-not $package.Success -or -not $sdk.Success) { throw 'Incomplete APK badging.' }
$abiLine = [regex]::Match($badging, '(?m)^native-code:\s*(.*)$')
$abis = @()
if ($abiLine.Success) {
    $abis = @([regex]::Matches($abiLine.Groups[1].Value, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value })
    if ($abis.Count -eq 0) { throw 'Invalid native-code badging.' }
}
$code = [long]$package.Groups[2].Value
$manifest = [ordered]@{
    schemaVersion = 1; applicationId = $package.Groups[1].Value; versionCode = $code
    versionName = $package.Groups[3].Value; channel = $Channel; commitSha = $CommitSha
    minSdk = [int]$sdk.Groups[1].Value; abis = $abis; certificateSha256 = $CertificateSha256
    apkSha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    apkSize = [long]$apk.Length; apkName = $apk.Name; sourceRef = $SourceRef
}
$path = Join-Path $apk.DirectoryName "update-$code.json"
[IO.File]::WriteAllText($path, (($manifest | ConvertTo-Json -Depth 4) + "`n"), [Text.UTF8Encoding]::new($false))
