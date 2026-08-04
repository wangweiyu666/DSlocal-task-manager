[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$AndroidHome = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found." }
if ([string]::IsNullOrWhiteSpace($AndroidHome)) { throw 'ANDROID_HOME is required.' }
$apkanalyzer = Join-Path $AndroidHome 'cmdline-tools\latest\bin\apkanalyzer.bat'
$apksigner = Get-ChildItem -LiteralPath (Join-Path $AndroidHome 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if (-not (Test-Path -LiteralPath $apkanalyzer -PathType Leaf)) { throw 'apkanalyzer.bat not found.' }
if (-not $apksigner) { throw 'apksigner.bat not found.' }

$manifestText = (& $apkanalyzer manifest print $Apk) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect APK manifest.' }
[xml]$manifest = $manifestText
$androidNs = 'http://schemas.android.com/apk/res/android'
$expectedPermissions = @(
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    'android.permission.VIBRATE',
    'android.permission.WAKE_LOCK',
    'com.ds.localtaskmanager.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
)
$actualPermissions = @($manifest.manifest.'uses-permission' | ForEach-Object { $_.GetAttribute('name', $androidNs) } | Sort-Object)
if (Compare-Object ($expectedPermissions | Sort-Object) $actualPermissions) {
    throw "Unexpected final permission set: $($actualPermissions -join ', ')"
}
if ($manifest.manifest.package -ne 'com.ds.localtaskmanager') { throw 'Unexpected release package name.' }
$application = $manifest.manifest.application
if ($application.GetAttribute('debuggable', $androidNs) -eq 'true') { throw 'Release APK is debuggable.' }
if ($application.GetAttribute('usesCleartextTraffic', $androidNs) -ne 'false') { throw 'Cleartext traffic is not disabled.' }

$components = @($application.activity) + @($application.receiver) + @($application.provider) + @($application.service)
$exported = @($components) | Where-Object { $_ -and $_.GetAttribute('exported', $androidNs) -eq 'true' }
$expectedExported = @{
    'com.ds.localtaskmanager.MainActivity' = ''
    'androidx.work.impl.background.systemjob.SystemJobService' = 'android.permission.BIND_JOB_SERVICE'
    'androidx.work.impl.diagnostics.DiagnosticsReceiver' = 'android.permission.DUMP'
    'androidx.profileinstaller.ProfileInstallReceiver' = 'android.permission.DUMP'
}
if ($exported.Count -ne $expectedExported.Count) {
    throw "Unexpected exported component count: $($exported.Count)"
}
foreach ($component in $exported) {
    $name = $component.GetAttribute('name', $androidNs)
    $permission = $component.GetAttribute('permission', $androidNs)
    if (-not $expectedExported.ContainsKey($name) -or $expectedExported[$name] -ne $permission) {
        throw "Unexpected exported component or protection: $name ($permission)"
    }
}

& $apksigner verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
Write-Host 'APK manifest and signature audit passed.'
