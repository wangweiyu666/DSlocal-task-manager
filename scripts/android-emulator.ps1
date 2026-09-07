[CmdletBinding()]
param(
    [ValidateSet("doctor", "setup", "start", "wait", "test", "stop")]
    [string]$Action = "doctor",
    [ValidateSet(26, 33, 35)]
    [int]$ApiLevel = 35,
    [switch]$Visible,
    [switch]$ColdBoot
)

$ErrorActionPreference = "Stop"
$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$javaRoot = if ($env:JAVA_HOME) {
    $env:JAVA_HOME
} else {
    if (-not (Get-Command java -CommandType Application -ErrorAction SilentlyContinue)) {
        throw "java was not found on the user PATH and JAVA_HOME is not set."
    }
    $javaHomeLine = & java -XshowSettings:properties -version 2>&1 |
        Select-String -Pattern "^\s*java\.home\s*=" |
        Select-Object -First 1
    if (-not $javaHomeLine) {
        throw "Unable to discover JAVA_HOME from java on the user PATH."
    }
    ($javaHomeLine.ToString() -replace "^\s*java\.home\s*=\s*", "").Trim()
}
$avdName = "local-task-manager-api$ApiLevel"
$emulatorPort = @{ 26 = 5554; 33 = 5556; 35 = 5558 }[$ApiLevel]
$targetSerial = "emulator-$emulatorPort"
$image = "system-images;android-$ApiLevel;google_apis;x86_64"
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$avdManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
$gradle = Join-Path $PSScriptRoot "..\gradlew.bat"

function Require-Tool([string]$Path, [string]$Name) {
    if (-not (Test-Path $Path)) {
        throw "$Name not found: $Path"
    }
}

function Get-TargetSerial {
    Require-Tool $adb "adb"
    $device = (& $adb devices) | Select-String -Pattern "^$([regex]::Escape($targetSerial))\s+device$"
    if ($device) {
        return $targetSerial
    }
    throw "$avdName is not running. Run: .\scripts\android-emulator.cmd start -ApiLevel $ApiLevel"
}

function Wait-ForBoot {
    Require-Tool $adb "adb"
    $deadline = (Get-Date).AddMinutes(4)
    do {
        $device = (& $adb devices) |
            Select-String -Pattern "^$([regex]::Escape($targetSerial))\s+device$" |
            Select-Object -First 1
        if ($device) {
            $booted = ([string](& $adb -s $targetSerial shell getprop sys.boot_completed 2>$null)).Trim()
            if ($booted -eq "1") {
                & $adb -s $targetSerial shell settings put global window_animation_scale 0
                & $adb -s $targetSerial shell settings put global transition_animation_scale 0
                & $adb -s $targetSerial shell settings put global animator_duration_scale 0
                Write-Host "Ready: $targetSerial ($(& $adb -s $targetSerial shell getprop ro.build.version.release))"
                return
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Emulator did not finish booting within four minutes."
}

function Repair-AvdMetadata {
    $configPath = Join-Path $env:USERPROFILE ".android\avd\$avdName.avd\config.ini"
    if (-not (Test-Path $configPath)) {
        throw "AVD configuration not found: $configPath"
    }
    $content = Get-Content $configPath | Where-Object {
        $_ -notmatch "^(AvdId|avd\.id|avd\.name|avd\.ini\.displayname)="
    }
    @("AvdId=$avdName", "avd.ini.displayname=$avdName") + $content |
        Set-Content $configPath -Encoding ascii
}

switch ($Action) {
    "doctor" {
        @{
            SDK = $sdkRoot
            Java = $javaRoot
            AVD = $avdName
            Serial = $targetSerial
            Adb = Test-Path $adb
            Emulator = Test-Path $emulator
            SdkManager = Test-Path $sdkManager
            AvdInstalled = Test-Path (Join-Path $env:USERPROFILE ".android\avd\$avdName.avd")
        }.GetEnumerator() | Sort-Object Name | Format-Table -AutoSize
    }
    "setup" {
        Require-Tool $sdkManager "sdkmanager"
        Require-Tool $avdManager "avdmanager"
        $env:JAVA_HOME = $javaRoot
        & $sdkManager --sdk_root=$sdkRoot --install "platform-tools" "emulator" $image
        if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
        if (-not (Test-Path (Join-Path $env:USERPROFILE ".android\avd\$avdName.avd"))) {
            "no" | & $avdManager create avd --force --name $avdName --package $image --device "pixel_6"
            if ($LASTEXITCODE -ne 0) { throw "avdmanager failed with exit code $LASTEXITCODE" }
        }
        Repair-AvdMetadata
        Write-Host "Configured $avdName"
    }
    "start" {
        Require-Tool $emulator "emulator"
        $arguments = @("-avd", $avdName, "-port", $emulatorPort, "-no-audio", "-no-boot-anim", "-gpu", "auto")
        if (-not $Visible) { $arguments += "-no-window" }
        if ($ColdBoot) { $arguments += "-no-snapshot-load" }
        $windowStyle = if ($Visible) { "Normal" } else { "Hidden" }
        Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $windowStyle | Out-Null
        Wait-ForBoot
    }
    "wait" {
        Wait-ForBoot
    }
    "test" {
        $serial = Get-TargetSerial
        $env:ANDROID_HOME = $sdkRoot
        $env:ANDROID_SDK_ROOT = $sdkRoot
        $env:JAVA_HOME = $javaRoot
        $env:ANDROID_SERIAL = $serial
        & $gradle testOfflineDebugUnitTest testProductionDebugUnitTest connectedOfflineDebugAndroidTest connectedProductionDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Gradle tests failed with exit code $LASTEXITCODE" }
    }
    "stop" {
        $serial = Get-TargetSerial
        & $adb -s $serial emu kill
    }
}
