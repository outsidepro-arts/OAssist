Set-StrictMode -Version Latest

function Get-OAssistCiMetadata {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Ref, [Parameter(Mandatory)][string]$RunNumber, [Parameter(Mandatory)][string]$VersionCodeBase)
    foreach ($value in @($RunNumber, $VersionCodeBase)) {
        if ($value -cnotmatch '\A(0|[1-9][0-9]*)\z') { throw 'Version code inputs must be non-negative decimal integers.' }
    }
    $run = [long]0; $base = [long]0
    if (-not [long]::TryParse($RunNumber, [ref]$run) -or -not [long]::TryParse($VersionCodeBase, [ref]$base) -or
        $run -lt 1 -or $run -gt 2100000000 -or $base -gt 2100000000 -or $run + $base -gt 2100000000) {
        throw 'Version code is outside the supported range.'
    }
    $variant = 'Debug'; $name = ''; $prerelease = $false
    if ($Ref -cne 'refs/heads/master') {
        if ($Ref -cnotmatch '\Arefs/tags/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(beta|rc)(\.?[1-9][0-9]*)?)?\z') { throw 'Unsupported Git ref.' }
        $variant = 'Release'; $name = $Ref.Substring('refs/tags/v'.Length)
        $prerelease = $name.Contains('-')
    }
    [pscustomobject]@{ VersionCode = [int]($base + $run); Variant = $variant; VersionName = $name; Prerelease = $prerelease }
}

function Test-OAssistTestReports {
    [CmdletBinding()]
    param([Parameter(Mandatory)][AllowEmptyCollection()][string[]]$ReportFiles)
    $total = 0; $failed = 0; $skipped = 0
    foreach ($file in $ReportFiles) {
        $xml = [System.Xml.XmlDocument]::new()
        $xml.XmlResolver = $null
        $xml.Load($file)
        if ($xml.SelectSingleNode("//*[local-name()='testsuite' or local-name()='testsuites']/*[local-name()='failure' or local-name()='error']")) { throw 'A test suite reported an error or failure.' }
        foreach ($suite in $xml.SelectNodes("//*[local-name()='testsuite' or local-name()='testsuites']")) {
            foreach ($attribute in @('failures', 'errors')) {
                if ($suite.HasAttribute($attribute)) {
                    $count = 0
                    if (-not [int]::TryParse($suite.GetAttribute($attribute), [ref]$count) -or $count -ne 0) { throw "A test suite reported $attribute or an invalid count." }
                }
            }
        }
        foreach ($test in $xml.SelectNodes("//*[local-name()='testcase']")) {
            $total++
            if ($test.SelectSingleNode("./*[local-name()='failure' or local-name()='error']")) { $failed++ }
            if ($test.SelectSingleNode("./*[local-name()='skipped']")) { $skipped++ }
        }
    }
    if ($total -eq 0 -or $failed -gt 0 -or $skipped -gt 0) { throw "Invalid test reports: tests=$total failures=$failed skipped=$skipped" }
    [pscustomobject]@{ Tests = $total; Failures = $failed; Skipped = $skipped }
}

function Get-OAssistBuildTool {
    param([string]$Directory, [string]$Name)
    $suffix = if ($IsWindows) { if ($Name -eq 'apksigner') { '.bat' } else { '.exe' } } else { '' }
    $path = Join-Path $Directory ($Name + $suffix)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Build tool not found: $path" }
    return $path
}

function Test-OAssistApkDetails {
    param([string]$SignerOutput, [string]$Badging, [int]$ExpectedVersionCode, [string]$ExpectedVersionName, [string]$Variant, [string]$ExpectedCertificateSha256)
    $digest = $ExpectedCertificateSha256.Replace(':', '').ToLowerInvariant()
    if ($digest -cnotmatch '\A[0-9a-f]{64}\z') { throw 'Expected certificate SHA-256 must contain 64 hexadecimal digits.' }
    $matches = [regex]::Matches($SignerOutput, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
    if ($matches.Count -ne 1 -or $matches[0].Groups[1].Value.Replace(':', '').ToLowerInvariant() -cne $digest) { throw 'APK signer certificate does not match.' }
    $package = [regex]::Match($Badging, "(?m)^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'")
    if (-not $package.Success -or $package.Groups[1].Value -cne 'ru.outsideproarts.oassist' -or $package.Groups[2].Value -cne [string]$ExpectedVersionCode -or
        [string]::IsNullOrWhiteSpace($package.Groups[3].Value) -or ($ExpectedVersionName -and $package.Groups[3].Value -cne $ExpectedVersionName)) { throw 'APK package or version does not match.' }
    $debuggable = [regex]::IsMatch($Badging, '(?m)^application-debuggable\s*$')
    if ($debuggable -ne ($Variant -ceq 'Debug')) { throw 'APK debuggable state does not match variant.' }
}

function Confirm-OAssistApk {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ApkPath, [Parameter(Mandatory)][string]$BuildToolsDirectory, [Parameter(Mandatory)][int]$ExpectedVersionCode,
        [string]$ExpectedVersionName = '', [Parameter(Mandatory)][ValidateSet('Debug','Release')][string]$Variant, [Parameter(Mandatory)][string]$ExpectedCertificateSha256)
    if ($ExpectedVersionCode -le 0 -or -not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'APK path or expected version code is invalid.' }
    $PSNativeCommandUseErrorActionPreference = $false
    $signer = Get-OAssistBuildTool $BuildToolsDirectory 'apksigner'; $aapt = Get-OAssistBuildTool $BuildToolsDirectory 'aapt2'
    $signerOutput = (& $signer verify --verbose --print-certs $ApkPath 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $badging = (& $aapt dump badging $ApkPath 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
    Test-OAssistApkDetails $signerOutput $badging $ExpectedVersionCode $ExpectedVersionName $Variant $ExpectedCertificateSha256
    [pscustomobject]@{ ApkPath = $ApkPath; Verified = $true }
}

function New-OAssistSignedApk {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$UnsignedApk, [Parameter(Mandatory)][string]$SignedApk, [Parameter(Mandatory)][string]$BuildToolsDirectory,
        [Parameter(Mandatory)][int]$ExpectedVersionCode, [string]$ExpectedVersionName = '', [Parameter(Mandatory)][ValidateSet('Debug','Release')][string]$Variant,
        [Parameter(Mandatory)][string]$ExpectedCertificateSha256)
    foreach ($name in @('OASSIST_KEYSTORE_BASE64','OASSIST_KEYSTORE_PASSWORD','OASSIST_KEY_ALIAS','OASSIST_KEY_PASSWORD')) {
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name))) { throw "Missing signing environment variable: $name" }
    }
    if (-not (Test-Path -LiteralPath $UnsignedApk -PathType Leaf)) { throw 'Input APK does not exist.' }
    $source = [IO.Path]::GetFullPath($UnsignedApk); $destination = [IO.Path]::GetFullPath($SignedApk); $hashPath = $destination + '.sha256'
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if ([string]::Equals($source, $destination, $comparison) -or [string]::Equals($source, $hashPath, $comparison)) { throw 'Input and output paths must differ.' }
    if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($destination)) -PathType Container)) { throw 'Output directory does not exist.' }
    if ((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $hashPath)) { throw 'Output APK or checksum already exists.' }
    $root = if ($env:RUNNER_TEMP) { [IO.Path]::GetFullPath($env:RUNNER_TEMP) } else { [IO.Path]::GetFullPath([IO.Path]::GetTempPath()) }
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Temporary directory does not exist.' }
    $temp = Join-Path $root ('oassist-ci-' + [guid]::NewGuid().ToString('N'))
    $prefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not [IO.Path]::GetFullPath($temp).StartsWith($prefix, $comparison)) { throw 'Unsafe temporary path.' }
    New-Item -ItemType Directory -Path $temp -ErrorAction Stop | Out-Null
    try {
        $key = Join-Path $temp 'signing.jks'; $staged = Join-Path $temp 'signed.apk'
        [IO.File]::WriteAllBytes($key, [Convert]::FromBase64String($env:OASSIST_KEYSTORE_BASE64))
        $signer = Get-OAssistBuildTool $BuildToolsDirectory 'apksigner'
        $PSNativeCommandUseErrorActionPreference = $false
        $signOutput = (& $signer sign --ks $key --ks-key-alias $env:OASSIST_KEY_ALIAS --ks-pass env:OASSIST_KEYSTORE_PASSWORD --key-pass env:OASSIST_KEY_PASSWORD --out $staged $source 2>&1 | Out-String)
        $signExit = $LASTEXITCODE
        if ($signExit -ne 0) {
            $secrets = @('OASSIST_KEYSTORE_BASE64','OASSIST_KEYSTORE_PASSWORD','OASSIST_KEY_ALIAS','OASSIST_KEY_PASSWORD') | ForEach-Object { [Environment]::GetEnvironmentVariable($_) } | Sort-Object Length -Descending
            foreach ($secret in $secrets) {
                if ($secret) { $signOutput = $signOutput.Replace($secret, '[redacted]') }
            }
            if ($signOutput.Length -gt 3000) { $signOutput = $signOutput.Substring(0, 3000) + '...' }
            throw "APK signing failed (exit $signExit): $($signOutput.Trim())"
        }
        Confirm-OAssistApk -ApkPath $staged -BuildToolsDirectory $BuildToolsDirectory -ExpectedVersionCode $ExpectedVersionCode -ExpectedVersionName $ExpectedVersionName -Variant $Variant -ExpectedCertificateSha256 $ExpectedCertificateSha256 | Out-Null
        $hash = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash.ToLowerInvariant()
        $stagedHash = Join-Path $temp 'signed.apk.sha256'
        [IO.File]::WriteAllText($stagedHash, "$hash  $([IO.Path]::GetFileName($destination))`n", [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($staged, $destination)
        [IO.File]::Move($stagedHash, $hashPath)
        [pscustomobject]@{ ApkPath = $destination; Sha256Path = $hashPath }
    } finally {
        if (Test-Path -LiteralPath $temp) {
            $fullTemp = [IO.Path]::GetFullPath($temp)
            $item = Get-Item -LiteralPath $fullTemp -Force
            if (-not $fullTemp.StartsWith($prefix, $comparison) -or -not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing unsafe temporary directory cleanup.' }
            Remove-Item -LiteralPath $fullTemp -Recurse -Force -ErrorAction Stop
        }
    }
}

Export-ModuleMember -Function Get-OAssistCiMetadata, Test-OAssistTestReports, Confirm-OAssistApk, New-OAssistSignedApk
