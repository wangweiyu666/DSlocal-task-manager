[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$KeyStorePath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\local-task-manager-release.jks'),
    [string]$PropertiesPath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle\local-task-manager-signing.properties')
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'JAVA_HOME is required.' }
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw "keytool not found under JAVA_HOME." }
if (Test-Path -LiteralPath $KeyStorePath) { throw "Refusing to overwrite existing release keystore." }
if (Test-Path -LiteralPath $PropertiesPath) { throw "Refusing to overwrite existing signing properties." }

function New-Secret([int]$Bytes = 32) {
    $buffer = [byte[]]::new($Bytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$storePassword = New-Secret
$keyPassword = New-Secret
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $KeyStorePath) | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $PropertiesPath) | Out-Null

& $keytool -genkeypair -keystore $KeyStorePath -storepass $storePassword -keypass $keyPassword `
    -storetype JKS -alias 'dstationery-release' -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10950 `
    -dname 'CN=rochelimit_cw' -noprompt
if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }

$normalizedKeyStore = $KeyStorePath.Replace('\', '/')
$properties = @(
    "storeFile=$normalizedKeyStore"
    "storePassword=$storePassword"
    'keyAlias=dstationery-release'
    "keyPassword=$keyPassword"
) -join [Environment]::NewLine
[IO.File]::WriteAllText($PropertiesPath, $properties + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))

Write-Host "Release keystore created at: $KeyStorePath"
Write-Host "Signing properties created at: $PropertiesPath"
Write-Host 'Back up the keystore and password file separately. Neither file is stored in Git.'
