# Windows 与 Android 工具

通过用户 PATH 调用 `git`、`node`、`npm`、`java`、`adb`，Gradle 使用 `.\gradlew.bat`；不固化机器路径，不为简单命令套 `pwsh -Command`。需要环境变量、条件或对象管道时使用 PowerShell 7 `pwsh`，不用旧 `powershell`。安装和设备操作按当次授权执行；测试范围只见 [最小测试方案](minimal-testing.md)。

## 环境与 PATH

Cloud 命令前确认 Node 22+，Android 构建前确认 JDK 17 和本地 SDK 的 adb：

```powershell
node --version
java --version
$projectAndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_HOME = $projectAndroidSdk
$env:PATH = "$(Join-Path $projectAndroidSdk 'platform-tools');$env:PATH"
adb version
.\gradlew.bat --version
```

解析不符时用 `Get-Command <工具> -All` 或 `where.exe <工具>`，只对当前进程前置正确目录。Node 候选为 `%ProgramFiles%\nodejs` 或 `%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin`；PowerShell 7 候选为同一运行时的 `dependencies\native\powershell` 或 `%LOCALAPPDATA%\Microsoft\WindowsApps`。JDK 通过当前进程 `JAVA_HOME` 和其 `bin` 配置，避免永久修改环境。

Wrapper 下载受阻时可查找 `%USERPROFILE%\.gradle\wrapper\dists` 中已缓存的 `gradle.bat`；核对其版本与 `gradle/wrapper/gradle-wrapper.properties` 完全一致，再把对应 `bin` 前置到当前 PATH。不要直接选择最高或最新版本。

### 使用已缓存的 Robolectric 运行离线单元测试

Gradle 的 `--offline` 不会关闭 Robolectric 自己的 Maven 下载。若用户缓存已有目标 Android 运行包，可显式指定缓存目录：

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$projectRobolectricJar = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\repository\org\robolectric\android-all-instrumented') `
  -Filter '*15-robolectric-12650502-i7.jar' -Recurse | Select-Object -First 1
if (-not $projectRobolectricJar) { throw '缺少当前 Robolectric Android 15 运行包' }
$projectPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS = "$projectPreviousJavaOptions -Drobolectric.offline=true `"-Drobolectric.dependency.dir=$($projectRobolectricJar.DirectoryName)`""
  .\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release --offline --no-daemon
} finally {
  $env:JAVA_TOOL_OPTIONS = $projectPreviousJavaOptions
}
```

Java 必须能读取该 JAR；缓存访问受限时可能退回 stub 并出现 `NoSuchFieldError: noncompatWidthPixels`，应先排除缓存与权限问题。

## Android 模拟器

仅用于获准设备验收或故障诊断，日常提交不执行全设备套件。

| API / AVD | 用途 |
| --- | --- |
| 35 / `local-task-manager-api35` | 目标版本交互 |
| 33 / `local-task-manager-api33` | Android 13 通知权限 |
| 26 / `local-task-manager-api26` | minSdk 兼容 |

`scripts/android-emulator.cmd` 从 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `%LOCALAPPDATA%\Android\Sdk` 找 SDK，仅为该脚本进程绕过执行策略。初次配置需 SDK 中已有 `cmdline-tools/latest`；setup 会安装缺失工具、Google APIs x86_64 镜像并创建 AVD。

```powershell
.\scripts\android-emulator.cmd doctor -ApiLevel 35
.\scripts\android-emulator.cmd setup -ApiLevel 35
.\scripts\android-emulator.cmd start -ApiLevel 35
.\scripts\android-emulator.cmd stop -ApiLevel 35
```

把 API 改为 26/33 可操作对应 AVD。start 默认隐藏窗口并关闭动画；观察交互可加 `-Visible`，快照故障可加 `-ColdBoot`。加速故障先运行 doctor，再将 SDK 的 emulator 目录临时加入 PATH 执行 `emulator -accel-check`。

`test` 子命令会执行双变体完整单元和仪器测试，**只用于故障诊断，不作日常入口**。定向仪器任务为 `connectedOfflineDebugAndroidTest` / `connectedProductionDebugAndroidTest`，用 `-Pandroid.testInstrumentationRunnerArguments.class=<完整类名>` 缩小范围。报告在 `app/build/reports/tests/` 和 `app/build/reports/androidTests/`。

## production 截图

基线固定为 Windows、Temurin `17.0.19+10`、Asia/Hong_Kong、简体中文和 UTF-8：

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Hong_Kong -Duser.language=zh -Duser.country=CN -Dfile.encoding=UTF-8'
.\gradlew.bat -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest --no-daemon
```

长路径故障沿用 Android CI 的临时 W: 项目、G: Gradle 缓存映射，先确认盘符空闲，结束后移除本次映射。截图报告位于 `app/build/reports/screenshotTest/`。旧版本验收数量见 [历史记录](verification-records.md#initial-release)。
