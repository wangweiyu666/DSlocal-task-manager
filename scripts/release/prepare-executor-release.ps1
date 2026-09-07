[CmdletBinding()]
param(
    [string]$Version = '0.1.0-alpha.13',
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ArchiveRoot = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'DStationery-Executor-Releases')
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repo
try {
    if (git status --porcelain) { throw 'Working tree must be clean.' }
    $commit = (git rev-parse HEAD).Trim()
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidHome
    & .\gradlew.bat testProductionDebugUnitTest lintProductionRelease assembleProductionRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Production executor verification build failed.' }

    $sourceApk = Join-Path $repo 'app\build\outputs\apk\production\release\app-production-release.apk'
    & (Join-Path $PSScriptRoot 'audit-apk.ps1') -Apk $sourceApk -AndroidHome $AndroidHome `
        -ExpectedPackage 'com.ds.localtaskmanager.connected.production' `
        -ExpectedVersionName "$Version-executor" -ExpectedVersionCode 14 -Networked
    if ($LASTEXITCODE -ne 0) { throw 'Production executor APK audit failed.' }

    $archive = Join-Path $ArchiveRoot $Version
    New-Item -ItemType Directory -Force -Path $archive | Out-Null
    $apk = Join-Path $archive "DStationery-executor-$Version.apk"
    Copy-Item -LiteralPath $sourceApk -Destination $apk -Force
    Copy-Item -LiteralPath (Join-Path $repo 'THIRD_PARTY_NOTICES.txt') -Destination $archive -Force
    $mapping = Join-Path $repo 'app\build\outputs\mapping\productionRelease\mapping.txt'
    if (Test-Path -LiteralPath $mapping) { Copy-Item -LiteralPath $mapping -Destination $archive -Force }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$apk.sha256", "$hash  $(Split-Path -Leaf $apk)`n", [Text.UTF8Encoding]::new($false))

    $evidence = [ordered]@{
        schemaVersion = 2
        releaseKind = 'executor-production'
        status = 'candidate'
        version = $Version
        commit = $commit
        packageName = 'com.ds.localtaskmanager.connected.production'
        cloudEnvironment = 'production'
        apkSha256 = $hash
        localChecks = [ordered]@{ jvm = 'passed'; lintRelease = 'passed'; releaseAudit = 'passed' }
        deviceChecks = [ordered]@{ api26 = 'pending'; api33 = 'pending'; api35 = 'pending'; realExecutorDevice = 'pending' }
    }
    $evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $archive 'release-evidence.json') -Encoding utf8NoBOM
    Write-Host "Production executor candidate prepared at: $archive"
} finally {
    Pop-Location
}
