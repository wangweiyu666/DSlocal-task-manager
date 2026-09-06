# 心情记录交付验证（2026-09-06）

验证代码为 Git 基线 `8c5124fdb648d5319af4bc3b1abce2b23b160bb6` 加本次未提交的心情功能改动。未推送、部署或发布。测试由测试代理执行，主代理审查新增断言、实际渲染图和最终测试 XML，并独立复核 APK SHA-256。

## 最终检查

| 检查 | 结果 |
| --- | --- |
| Offline 完整单元测试 | 125 通过，0 失败／错误／跳过 |
| Connected 完整单元测试 | 137 通过，0 失败／错误／跳过 |
| Production 完整单元测试 | 137 通过，0 失败／错误／跳过 |
| Android Lint | offlineRelease、connectedDebug、productionDebug 均通过 |
| API 35 心情设备测试 | 6 通过，0 失败 |
| Offline 截图验证 | 28 通过，包含 5 张新增心情预览 |
| Web | 66 测试通过；typecheck、双版本构建、协议和资源边界检查通过 |
| Cloud | 33 测试通过；typecheck 通过 |
| 本地 Worker HTTP smoke | 通过；使用隔离 `.wrangler/mood-smoke`，测试后停止 Worker |
| 差异空白检查 | `git diff --check` 通过 |

Android 使用 JDK 17、Android SDK 和本地 Robolectric 缓存。受限环境下 JVM 读取缓存和模拟器加速被拒绝，允许宿主执行后测试正常完成，没有跳过相关测试。

最终运行任务：

```text
.\gradlew.bat testOfflineDebugUnitTest testConnectedDebugUnitTest testProductionDebugUnitTest --offline --no-daemon
.\gradlew.bat lintOfflineRelease lintConnectedDebug lintProductionDebug --offline --no-daemon
.\gradlew.bat connectedOfflineDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ds.localtaskmanager.ui.execution.MoodSectionTest --offline --no-daemon
.\gradlew.bat validateOfflineDebugScreenshotTest --offline --no-daemon
.\gradlew.bat assembleOfflineDebug assembleConnectedDebug assembleProductionDebug --offline --no-daemon
npm --prefix web test
npm --prefix web run build:all
npm --prefix web run typecheck
npm --prefix cloud test
npm --prefix cloud run typecheck
npm --prefix cloud run smoke:local
```

本地 smoke 使用 `CLOUD_SMOKE_BASE=http://127.0.0.1:8797` 和隔离的本地数据库。缓存与当前进程环境配置遵循 [Windows 工具指南](windows-tooling.md)。完整 Android 日志保存在 `app/build/reports/mood/full-android-matrix.txt`，Lint 日志保存在 `app/build/reports/mood/android-lint.txt`。

## 验证范围

- 五档实际触摸点选、拖动吸附、初始未选择、无障碍状态与逐档 `SetProgress`、只读答案。键盘用 OS IME 可见性断言确认已弹出，再验证完成按钮仍可见。
- 浅色、深色、1.6 倍字体及完整任务详情渲染；主代理检查新增预览。
- 自动保存、失败后重试、保存期间继续编辑、完成前保存顺序、返回期间新输入、完成锁定、撤销后保留答案。
- 每日实例隔离、单日执行类型切换清理、固定积分、Room 升级、旧备份解码、心情快照替换与合并；较新的撤销状态继续遵循现有实例合并规则。
- 协议往返及非法参数，Web 任务／模板备份和单日调整编辑；联网完成／撤销／重新完成、重复与延迟事件、管理员结果选择及单日执行类型解析。权限和多执行者隔离沿用既有测试及本地 HTTP smoke。

设备交互验证使用 API 35 模拟器；未执行实体设备触感体验或人工 TalkBack 朗读验收。联网后端改动仅在本地验证，尚未部署到下列远程环境。

## Debug APK

三个 APK 均包含五张本地 Fluent Emoji 3D 图标和 `assets/licenses/fluent-emoji.txt`。图标固定版本和许可见 [心情图标来源](mood-artwork.md)。构建使用上述已验证 Android 源码，复用同源 Gradle 输出，未重复运行已通过的完整测试。

| 环境 | APK（相对于仓库根目录） | 包名 |
| --- | --- | --- |
| 离线，不连接 API | `app/build/outputs/apk/offline/debug/app-offline-debug.apk` | `com.ds.localtaskmanager.debug` |
| Staging，`https://api-staging.rochelimit.me` | `app/build/outputs/apk/connected/debug/app-connected-debug.apk` | `com.ds.localtaskmanager.connected.debug` |
| Production，`https://api.rochelimit.me` | `app/build/outputs/apk/production/debug/app-production-debug.apk` | `com.ds.localtaskmanager.connected.production.debug` |

SHA-256：

```text
offline     BD0F09EF200E03263727620FF86F34896ECE9E67696EC079017E2C3E916A2558
connected   1C270A3D0504550721ADE9616F4002B2F5560CB3058F1B0415B130AC3F236FCF
production  63C1DEDB885731AF3F7690018AA4CA05709026F593F59795C7E86F4A637ABF09
```

Production Debug 是连接生产服务的调试安装包，构建它不代表已执行生产发布。
