# Generates the release signing keystore + keystore.properties (idempotent).
# ASCII-only: Windows PowerShell 5.1 reads .ps1 without BOM as ANSI.
param(
  [string]$Alias = 'oassist',
  [int]$ValidityDays = 10000,
  [string]$DName = 'CN=OAssist, OU=OAssist, O=OutsideProArts, L=Unknown, ST=Unknown, C=RU'
)

$ErrorActionPreference = 'Stop'

$root      = Split-Path -Parent $PSScriptRoot
$dir       = Join-Path $root 'keystore'
$keyStore  = Join-Path $dir 'oassist-release.jks'
$props     = Join-Path $root 'keystore.properties'
$keytool   = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { $keytool = 'C:\dev\jdk\jdk-21.0.10+7\bin\keytool.exe' }
if (-not (Test-Path $keytool)) { throw 'keytool.exe not found (set JAVA_HOME)' }

if ((Test-Path $keyStore) -and (Test-Path $props)) {
  "keystore already exists: $keyStore"
  "passwords: $props"
  exit 0
}

New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Random 24-char password (alnum, no ambiguous chars).
$alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
$bytes = New-Object 'byte[]' 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = -join (0..23 | ForEach-Object { $alphabet[$bytes[$_] % $alphabet.Length] })

& $keytool -genkeypair -v `
  -keystore $keyStore `
  -alias $Alias `
  -keyalg RSA -keysize 3072 `
  -validity $ValidityDays `
  -storetype PKCS12 `
  -storepass $password -keypass $password `
  -dname $DName
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

@(
  'storeFile=keystore/oassist-release.jks'
  "storePassword=$password"
  "keyAlias=$Alias"
  "keyPassword=$password"
) | Set-Content -Path $props -Encoding ASCII

"generated: $keyStore"
"passwords stored in: $props (gitignored, never commit)"
"backup BOTH files: losing the keystore means updates can never be signed again"
