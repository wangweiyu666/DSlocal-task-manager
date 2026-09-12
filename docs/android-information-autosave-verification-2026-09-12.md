# Android 信息告知自动保存验证（2026-09-12）

源码基于 `882489f008ce44104292249ee58ef2d67437eac9`，验证对象为本次未提交的工作区代码修改。开始时已有 AGENTS.md 和其他文档修改，本次保留这些修改。未推送、部署或安装到设备。

## 行为与验收

- 正文停止输入 500ms 后自动保存，页面显示保存状态，失败时保留正文并提供重试。
- 点击完成时先保存最新正文，等待正在进行的写入结束；成功后才完成，失败时保持未完成。完成期间及完成后禁止编辑正文。
- 返回、切到后台、复制和分享前保存最新正文；保存与刷新期间的新输入不会被旧快照覆盖。
- 清空正文能够持久化；空正文仍不能完成任务。保留 2000 Unicode 码点限制、完成锁定和撤销恢复规则。

## 实际结果

| 检查 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| InformationAutoSaveTest | 7 | 0 | 0 | 0 |
| W22ExecutionViewModelTest | 9 | 0 | 0 | 0 |
| MoodSaveRaceRegressionTest | 2 | 0 | 0 | 0 |
| W10ExecutionServiceTest（Room） | 13 | 0 | 0 | 0 |
| offlineDebug 截图校验 | 25 | 0 | 0 | 0 |

定向单元测试合计 31 条。新增 7 条分别验证防抖与重新进入、未到防抖时间立即完成、阻塞写入期间完成、返回与后台保存、失败阻止后续操作及重试、分享与清空、刷新竞态。Room 测试验证清空后的数据库重建读取、空正文禁止完成，以及既有完成锁定和撤销恢复。

两个 Debug 变体构建通过；`lintOfflineRelease` 和 `lintProductionDebug` 均为 `No issues found.`。离线发布清单不含 INTERNET/ACCESS_NETWORK_STATE 权限，offlineReleaseRuntimeClasspath 不含 okhttp、retrofit、ktor-client，也无未解析依赖。`git diff --check` 通过。

未执行完整单元集合、完整两变体测试矩阵或真机安装/UI/后台调度验收。截图为既有 25 项基线检查，不代表新增自动保存交互的真机证据。新增回归已纳入 daily 和 release；静态清单校验为 daily 44、release 155、full 181，不能作为整套运行通过数量。

## 命令与环境

JDK 17.0.19；使用本地 Android SDK 的 ADB 37.0.0 验证环境。Robolectric 使用已缓存的 Android 15 运行包。

```powershell
.\gradlew.bat -I build/information-autosave-shortpaths.init.gradle testProductionDebugUnitTest --tests com.ds.localtaskmanager.ui.execution.InformationAutoSaveTest --tests com.ds.localtaskmanager.ui.execution.W22ExecutionViewModelTest --tests com.ds.localtaskmanager.ui.execution.MoodSaveRaceRegressionTest --tests com.ds.localtaskmanager.data.W10ExecutionServiceTest assembleOfflineDebug assembleProductionDebug lintOfflineRelease lintProductionDebug validateOfflineDebugScreenshotTest --no-daemon --max-workers=2
```

以上组合运行的单元测试、构建与 Lint 完成；截图进程启动遇到 Windows 命令行长度限制。初始尝试另遇沙箱 Android 配置目录访问限制和离线依赖解析失败，恢复依赖后继续，未修改测试断言。这些启动失败不计入 JUnit 用例数量。

单元测试使用临时 init 脚本，在启动 Test 进程时将类路径映射到短路径；不改应用配置。截图随后按 CI 的短路径方式单独运行并成功：将项目映射为 W:、用户 Gradle 缓存映射为 G:，在 W: 下设置 `GRADLE_USER_HOME=G:\`，并设置 JVM 时区 Asia/Hong_Kong、语言 zh、国家 CN、编码 UTF-8。

```powershell
.\gradlew.bat validateOfflineDebugScreenshotTest --no-daemon --max-workers=2
.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --offline --no-daemon --max-workers=2
python scripts/testing/inspect-android-profiles.py
git diff --check -- app docs/android-test-profiles.md scripts/testing/android-daily.txt
```

依赖报告及生成的 offlineRelease 清单使用 PowerShell 检查网络客户端和权限，与 `scripts/ci/check-connectivity-boundaries.sh` 的检查项一致。

本地证据：`build/information-autosave-acceptance.log`、`build/information-autosave-screenshots.log`、`build/information-autosave-offline-dependencies.log`；JUnit XML 位于 `app/build/test-results/testProductionDebugUnitTest` 和 `app/build/test-results/validateOfflineDebugScreenshotTest`。

## 同源 Debug APK

构建后未修改应用源码；仅定向测试，不属于完整矩阵交付。两个 APK 均未安装或发布。

- offline：`app/build/outputs/apk/offline/debug/app-offline-debug.apk`，离线环境，包名 `com.ds.localtaskmanager.debug`，版本 `0.1.0-alpha.10-debug`（11）。SHA-256：`A874E1F6E3FEED6952B6687A0467B304AF198670AB70A394722E96B5A7160280`。
- production：`app/build/outputs/apk/production/debug/app-production-debug.apk`，正式 API 环境，包名 `com.ds.localtaskmanager.connected.production.debug`，版本 `0.1.0-alpha.14-executor-debug`（15）。SHA-256：`F70D036F0EE80443418092179172E9DAF3E8883E9F58EB53A51A98D061520AA1`。
