# Windows 命令行工具解析

本仓库在 Windows 上默认通过用户 `PATH` 直接调用工具，以缩短命令、减少路径和引号错误，并降低命令产生的 token 消耗。命令应写成 `git`、`node`、`npm`、`java`、`gradle`、`adb` 或仓库内的 `.\gradlew.bat`，不要在脚本和文档中固化某台机器上的可执行文件绝对路径，也不要为简单命令额外套一层 `pwsh -Command`。

只有命令确实需要 PowerShell 功能时才使用 PowerShell；此时选择 PowerShell 7 `pwsh`，避免旧版 Windows PowerShell 5.1。仓库根目录的 `AGENTS.md` 将这项约束应用到后续自动化调用。

## 命令选择

简单工具调用直接使用用户 `PATH`：

```text
git status --short
node --version
npm test
.\gradlew.bat testConnectedDebugUnitTest
adb devices
```

下列情况才使用 `pwsh`：

- 读取或临时调整 `$env:PATH`、`JAVA_HOME`、`ANDROID_HOME`；
- 使用 `Get-Command`、`Join-Path`、PowerShell 对象管道；
- 需要 PowerShell 的条件、循环或错误处理。

原则顺序为：短命令直接调用 → 必要时使用对应仓库脚本 → 确需 PowerShell 语法时使用 `pwsh`。

## PowerShell 7

先确认用户 `PATH` 可以解析 `pwsh`：

```powershell
where.exe pwsh
pwsh --version
```

当前 Codex Windows 环境通常提供以下用户级候选目录：

```text
%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell
%LOCALAPPDATA%\Microsoft\WindowsApps
```

只使用命令名启动 PowerShell 7：

```text
pwsh
```

不要用 `powershell` 代替；该名称通常解析到旧版 Windows PowerShell 5.1。

## Node.js 22+

当前 Windows 开发环境已安装官方 Node.js LTS `v24.19.0` 和 npm `11.17.0`，默认通过 `PATH` 中的 `%ProgramFiles%\nodejs` 解析。正常情况下直接使用短命令，不需要调整 PATH：

```text
node --version
npm --version
```

Cloudflare Wrangler 要求 Node.js 22 或更高版本。先检查当前 `PATH` 的实际解析结果：

```powershell
where.exe node
node --version
```

若系统级 Node 较旧，只在当前 PowerShell 进程中把 Codex 用户运行时前置到 `PATH`：

```powershell
$projectNodeBin = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin'
$env:PATH = "$projectNodeBin;$env:PATH"
node --version
npm --version
```

这不会修改系统或用户的永久环境变量。版本校验通过后继续使用短命令，例如：

```powershell
Set-Location cloud
npm run check
npm run migrate:local
```

## Gradle、JDK 与 Android SDK

优先使用仓库内 Wrapper：

```powershell
.\gradlew.bat --version
```

如果 Wrapper 因受限网络无法下载、但用户 Gradle 缓存已经包含目标版本，可从 `$env:USERPROFILE` 动态找到它，并只在当前进程前置其 `bin` 目录：

```powershell
$projectGradleBin = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\wrapper\dists') `
  -Filter gradle.bat -File -Recurse |
  Sort-Object FullName -Descending |
  Select-Object -First 1 -ExpandProperty DirectoryName
$env:PATH = "$projectGradleBin;$env:PATH"
gradle --version
```

JDK 和 Android SDK 也通过环境变量与用户 `PATH` 暴露，后续命令只使用短名称：

```powershell
$projectAndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$projectPlatformTools = Join-Path $projectAndroidSdk 'platform-tools'
$env:ANDROID_HOME = $projectAndroidSdk
$env:PATH = "$projectPlatformTools;$env:PATH"
java --version
adb version
gradle compileConnectedDebugKotlin --no-daemon
```

如果 `java --version` 不是 JDK 17，先用 `Get-Command java -All` 查找用户 PATH 中的 JDK 17；仍找不到时再设置当前进程的 `JAVA_HOME` 并把其 `bin` 前置到 `PATH`。

## 已验证的用户级来源

下列目录在当前 Windows 开发环境中可用。文档使用环境变量表达，避免绑定用户名或磁盘布局：

| 工具 | 用户级来源 |
| --- | --- |
| PowerShell 7 | `%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell` |
| Node.js 22+ | `%ProgramFiles%\nodejs`（当前默认）；Codex 缓存目录仅作为版本异常时的临时备用来源 |
| Gradle | `%USERPROFILE%\.gradle\wrapper\dists\<distribution>\<cache-key>\<version>\bin`（动态发现） |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| ADB | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |

## 调用规则

- 简单命令直接通过用户 `PATH` 调用，不额外启动 `pwsh`。
- 需要 PowerShell 时使用 `pwsh`，不默认使用 `powershell` 5.1。
- 先依赖用户 `PATH`；只有解析失败或版本不符时，才用 `where.exe` / `Get-Command -All` 排查解析顺序。
- 需要调整版本时只修改当前进程的 `PATH`，变量名使用任务专用名称。
- 只有 `PATH` 中不存在所需工具且没有可前置的用户运行时时，才临时使用绝对路径并在验收记录中说明原因。
- 不在仓库中记录密钥、令牌或带用户名的机器专属绝对路径。
