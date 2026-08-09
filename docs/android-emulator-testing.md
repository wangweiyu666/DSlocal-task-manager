# Android 模拟器测试环境

项目使用三档 Google APIs x86_64 AVD：

| API | AVD | 用途 |
| --- | --- | --- |
| 35 | `local-task-manager-api35` | 日常开发和目标版本回归 |
| 33 | `local-task-manager-api33` | Android 13 通知权限基线 |
| 26 | `local-task-manager-api26` | `minSdk` 兼容验证 |

统一入口为 `scripts/android-emulator.cmd`。它从 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `%LOCALAPPDATA%\Android\Sdk` 定位 SDK，并默认使用项目要求的 JDK 17。`.cmd` 只为当前脚本进程绕过 PowerShell 执行策略，不修改系统设置。

## 初次配置

```powershell
.\scripts\android-emulator.cmd setup -ApiLevel 35
.\scripts\android-emulator.cmd setup -ApiLevel 33
.\scripts\android-emulator.cmd setup -ApiLevel 26
```

`setup` 会安装缺失的 emulator、platform-tools 和系统镜像，然后创建固定名称的 AVD。command-line tools 必须已位于 SDK 的 `cmdline-tools/latest`。

## 日常测试

```powershell
# 无窗口启动主测试设备并等待开机
.\scripts\android-emulator.cmd start -ApiLevel 35

# 同时运行 JVM 与仪器测试
.\scripts\android-emulator.cmd test -ApiLevel 35

# 关闭模拟器
.\scripts\android-emulator.cmd stop -ApiLevel 35
```

需要观察交互时向 `start` 添加 `-Visible`；排查快照问题时添加 `-ColdBoot`。脚本在开机后关闭系统动画，以降低 Compose 测试波动。

## 窗口验收策略

- 日常提交：API 35 上运行 JVM 测试和 `connectedDebugAndroidTest`；
- W21 通知：API 33、35 均验证授权、拒绝、重启和时区变化；
- W22～W31：API 35 运行完整仪器测试，API 26 做关键流程兼容回归；
- W32：三档 AVD 全量回归，并至少增加一台 Android 13+ 真机。

## 归档验证结果

`v0.1.0-alpha.1` 发布前完成 JVM 97/97、Preview 截图 18/18、API 35 仪器测试 23/23 与 API 26 W31 关键流程 3/3；发布证据覆盖 API 26、33、35 和 Android 13+ 真机。最终发布 CI 见 [GitHub Actions 31285405667](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/31285405667)。

Preview 截图基线固定使用 Windows、Temurin `17.0.19+10`、`Asia/Hong_Kong`、简体中文与 UTF-8，避免字体、时区和渲染器差异造成伪变化：

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.timezone=Asia/Hong_Kong -Duser.language=zh -Duser.country=CN -Dfile.encoding=UTF-8'
.\gradlew.bat validateDebugScreenshotTest --no-daemon
```

报告位置：

- JVM：`app/build/reports/tests/testDebugUnitTest/index.html`
- 仪器测试：`app/build/reports/androidTests/connected/debug/index.html`

模拟器无法启动时先运行：

```powershell
.\scripts\android-emulator.cmd doctor -ApiLevel 35
& "$env:ANDROID_HOME\emulator\emulator.exe" -accel-check
```
