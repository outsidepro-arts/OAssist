param([string]$ReferenceApk = '', [string]$BuildToolsDirectory = '')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'OAssistCi.psm1') -Force

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    if (-not $threw) { throw "Expected failure: $Message" }
}

$debug = Get-OAssistCiMetadata 'refs/heads/master' '1' '1000'
Assert-True ($debug.VersionCode -eq 1001 -and $debug.Variant -ceq 'Debug' -and $debug.VersionName -ceq '' -and -not $debug.Prerelease) 'Debug metadata'
foreach ($tag in @('v1.2.3', 'v0.0.0-beta', 'v1.2.3-beta.1', 'v1.2.3-beta2', 'v1.2.3-rc', 'v1.2.3-rc.4', 'v1.2.3-rc5')) {
    $result = Get-OAssistCiMetadata "refs/tags/$tag" '1' '1000'
    Assert-True ($result.VersionName -ceq $tag.Substring(1) -and $result.Variant -ceq 'Release' -and $result.Prerelease -eq $tag.Contains('-')) "Tag $tag"
    Assert-True ($result.VersionCode -eq $debug.VersionCode) 'Shared run number produces shared version code'
}
foreach ($ref in @('refs/heads/main', 'refs/tags/v01.2.3', 'refs/tags/v1.02.3', 'refs/tags/v1.2.03', 'refs/tags/v1.2.3-alpha', 'refs/tags/v1.2.3-beta0', 'refs/tags/v1.2.3-rc.01', 'refs/tags/v1.2.3-rc.', 'refs/tags/v1.2.3/x', 'refs/tags/v1.2.3;echo')) {
    Assert-Throws { Get-OAssistCiMetadata $ref '1' '1000' } $ref
}
Assert-Throws { Get-OAssistCiMetadata "refs/tags/v1.2.3`n" '1' '1000' } 'Trailing newline in ref'
Assert-True ((Get-OAssistCiMetadata 'refs/heads/master' '1' '2099999999').VersionCode -eq 2100000000) 'Maximum version code'
foreach ($pair in @(@('0','0'), @('0','1000'), @('1','2100000000'), @('-1','1000'), @('01','1000'), @('x','1000'), @('99999999999999999999999','1'))) {
    Assert-Throws { Get-OAssistCiMetadata 'refs/heads/master' $pair[0] $pair[1] } "Invalid counter $pair"
}

$tempRoot = if ($env:TMPDIR) { $env:TMPDIR } else { [IO.Path]::GetTempPath() }
$temp = Join-Path $tempRoot ('oassist-ci-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $report = Join-Path $temp 'results.xml'
    [IO.File]::WriteAllText($report, '<testsuites xmlns="urn:junit"><testsuite><testcase name="one"/><testcase name="two"/></testsuite></testsuites>')
    Assert-True ((Test-OAssistTestReports @($report)).Tests -eq 2) 'Namespaced testcases counted'
    foreach ($body in @('<testsuite/>', '<testsuite><testcase><skipped/></testcase></testsuite>', '<testsuite><testcase><failure/></testcase></testsuite>', '<testsuite><testcase><error/></testcase></testsuite>', '<testsuites><testsuite><error/><testcase/></testsuite></testsuites>', '<testsuite errors="1"><testcase/></testsuite>')) {
        [IO.File]::WriteAllText($report, $body)
        Assert-Throws { Test-OAssistTestReports @($report) } "Bad report $body"
    }
    Assert-Throws { Test-OAssistTestReports @() } 'No reports'

    # Obtain the private command in its module, but keep assertions in this script's scope.
    $checkApkDetails = & (Get-Module OAssistCi) { Get-Command Test-OAssistApkDetails }
    $digest = 'a' * 64
    $signer = "Signer #1 certificate SHA-256 digest: $digest`nSigner #1 public key SHA-256 digest: $('b' * 64)`n"
    $manifest = "package: name='ru.outsideproarts.oassist' versionCode='42' versionName='1.2'`n"
    & $checkApkDetails $signer $manifest 42 '1.2' Release $digest
    & $checkApkDetails $signer ($manifest + "application-debuggable`n") 42 '' Debug $digest.ToUpperInvariant()
    Assert-Throws { & $checkApkDetails ($signer + "Signer #2 certificate SHA-256 digest: $digest`n") $manifest 42 '1.2' Release $digest } 'Multiple signers'
    Assert-Throws { & $checkApkDetails $signer $manifest 42 '1.2' Release ('b' * 64) } 'Public key is not certificate'
    Assert-Throws { & $checkApkDetails $signer $manifest 43 '1.2' Release $digest } 'Wrong version code'
    Assert-Throws { & $checkApkDetails $signer $manifest 42 'wrong' Release $digest } 'Wrong version name'
    Assert-Throws { & $checkApkDetails $signer ($manifest + "application-debuggable`n") 42 '' Release $digest } 'Unexpected debuggable'
    Assert-Throws { & $checkApkDetails $signer $manifest 42 '' Debug $digest } 'Missing debuggable'
    Assert-Throws { & $checkApkDetails $signer $manifest 42 '' Release 'bad' } 'Malformed digest'
    if ($ReferenceApk) {
        if (-not $BuildToolsDirectory) { throw 'BuildToolsDirectory is required with ReferenceApk.' }
        $originalEnvironment = @{}
        $envNames = @('RUNNER_TEMP','OASSIST_KEYSTORE_BASE64','OASSIST_KEYSTORE_PASSWORD','OASSIST_KEY_ALIAS','OASSIST_KEY_PASSWORD')
        foreach ($name in $envNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
        try {
            $runnerTemp = Join-Path $temp 'runner'; New-Item -ItemType Directory -Path $runnerTemp | Out-Null
            $key = Join-Path $temp 'test.jks'; $cert = Join-Path $temp 'test.cer'
            $env:RUNNER_TEMP = $runnerTemp
            $env:OASSIST_KEYSTORE_PASSWORD = 'temporary-test-password'; $env:OASSIST_KEY_PASSWORD = 'temporary-test-password'; $env:OASSIST_KEY_ALIAS = 'test-key'
            $PSNativeCommandUseErrorActionPreference = $false
            & keytool -genkeypair -keystore $key -storetype JKS -storepass $env:OASSIST_KEYSTORE_PASSWORD -keypass $env:OASSIST_KEY_PASSWORD -alias $env:OASSIST_KEY_ALIAS -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=CI test' *> $null
            if ($LASTEXITCODE -ne 0) { throw 'Test key generation failed.' }
            & keytool -exportcert -keystore $key -storepass $env:OASSIST_KEYSTORE_PASSWORD -alias $env:OASSIST_KEY_ALIAS -file $cert *> $null
            if ($LASTEXITCODE -ne 0) { throw 'Test certificate export failed.' }
            $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($cert)
            try { $fingerprint = $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256) } finally { $certificate.Dispose() }
            $env:OASSIST_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($key))
            $output = Join-Path $temp 'result.apk'
            $params = @{ UnsignedApk = $ReferenceApk; SignedApk = $output; BuildToolsDirectory = $BuildToolsDirectory; ExpectedVersionCode = 3; ExpectedVersionName = '1.1'; Variant = 'Release'; ExpectedCertificateSha256 = $fingerprint }
            $PSNativeCommandUseErrorActionPreference = $true
            $result = New-OAssistSignedApk @params
            Assert-True ((Test-Path -LiteralPath $result.ApkPath) -and (Test-Path -LiteralPath $result.Sha256Path)) 'Signed APK and checksum published'
            Assert-True (((Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()) -ceq ((Get-Content -LiteralPath $result.Sha256Path -Raw).Split(' ')[0])) 'Published checksum'
            Assert-Throws { New-OAssistSignedApk @params } 'Existing output cannot be overwritten'
            $originalFingerprint = '9a94d8a6cdd03884184da0a52afe27f93acae5b450d59c7e2703f8102381d7af'
            Assert-Throws { Confirm-OAssistApk $output $BuildToolsDirectory 3 '1.1' Release $originalFingerprint } 'Original certificate no longer matches'
            Assert-Throws { Confirm-OAssistApk $output $BuildToolsDirectory 4 '1.1' Release $fingerprint } 'Wrong version'
            Assert-True (@(Get-ChildItem -LiteralPath $runnerTemp -Force).Count -eq 0) 'Signing temporary directory removed'
            $params.SignedApk = Join-Path $temp 'failed.apk'
            $params.ExpectedCertificateSha256 = $originalFingerprint
            Assert-Throws { New-OAssistSignedApk @params } 'Verification failure after signing'
            Assert-True (-not (Test-Path -LiteralPath $params.SignedApk) -and @(Get-ChildItem -LiteralPath $runnerTemp -Force).Count -eq 0) 'Verification failure publishes nothing and cleans up'
            $params.ExpectedCertificateSha256 = $fingerprint
            $env:OASSIST_KEYSTORE_BASE64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('invalid keystore'))
            $failure = ''
            try { New-OAssistSignedApk @params | Out-Null } catch { $failure = $_.Exception.Message }
            Assert-True ($failure -match 'APK signing failed \(exit [1-9]' -and $failure.Length -gt 35 -and -not $failure.Contains($env:OASSIST_KEYSTORE_PASSWORD)) 'Signing failure has a sanitized native diagnostic'
            Assert-True (-not (Test-Path -LiteralPath $params.SignedApk) -and -not (Test-Path -LiteralPath ($params.SignedApk + '.sha256'))) 'Failed signing publishes nothing'
            Assert-True (@(Get-ChildItem -LiteralPath $runnerTemp -Force).Count -eq 0) 'Failed signing removes temporary key'
            $env:OASSIST_KEYSTORE_BASE64 = 'not base64!'
            Assert-Throws { New-OAssistSignedApk @params } 'Malformed base64'
            $env:OASSIST_KEY_ALIAS = ''
            Assert-Throws { New-OAssistSignedApk @params } 'Missing key alias'
            Assert-True (@(Get-ChildItem -LiteralPath $runnerTemp -Force).Count -eq 0) 'Invalid input leaves no temporary key'
        } finally {
            foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name]) }
        }
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force
}
Write-Host 'OAssist CI self-tests passed.'
