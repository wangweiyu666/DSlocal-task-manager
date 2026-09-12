# 通知、单选积分与条件步骤验证记录

## 源码和范围

基于 `882489f008ce44104292249ee58ef2d67437eac9` 的未提交工作区。保留本轮之前的信息告知自动保存修复和既有文档修改。本次不提交、不推送、不部署、不发布、不安装到设备。

最终应用源码指纹 SHA-256：`c36584206dc8935a7821d15cef20d8b7a5e77498b89795016ca4c0a199ae7454`。按相对路径排序，对 `app/src/main`、`app/src/offline`、`app/src/connected`、`app/src/production`、`app/schemas`、`shared/protocol`、`web/src`、`cloud/src` 及仓库存在的 Gradle 根配置逐项计算“路径、NUL、原始文件字节、NUL”，共 215 个文件；详情见本地 `build/choice-source-state.json`。它描述工作区内容，不是 Git 提交 SHA。

## 运行环境

Windows，JDK 17.0.19，Android SDK adb 37.0.0，Node 24.19.0。Gradle 项目和缓存分别使用临时 `W:`、`G:` 短路径，`GRADLE_USER_HOME=G:\`。Robolectric 使用本地 Android 15 instrumented 依赖，JVM 时区 Asia/Hong_Kong；截图另设置语言 zh、国家 CN、UTF-8。

Windows 路径切换使部分旧 Kotlin 增量缓存失效，Gradle 自动回退到非增量编译后完成，没有降低测试断言。

## 单元与浏览器验证

| 验证 | 通过 | 失败 | 跳过 | 证据 |
| --- | ---: | ---: | ---: | --- |
| production 完整单元套件 | 195 | 0 | 0 | `build/choice-final-android-retry.log`、`build/choice-full-unit-results` |
| 最后变更定向测试，去重后的用例 | 33 | 0 | 0 | `build/choice-handoff.log`、`build/choice-handoff-retry.log`；最终 9 条覆盖首次的测试输入失败 |
| Web Vitest 全套 | 86 | 0 | 0 | `build/choice-web-tests-final.log` |
| Cloud 全套 | 38 | 0 | 0 | `build/choice-cloud-check-final.log` |
| Playwright Chrome / Edge / Pixel 7 模拟 | 11 | 0 | 1 | `build/choice-web-e2e.log`、`build/choice-web-e2e-retry.log` |

首次完整 Android 执行 195 条，4 条失败：旧备份本地 ID 校验、完成实例的更新时间被后续导入覆盖、两条积分组迁移排序回归；修复后完整执行全部通过。随后增加并完善重复任务配置与备份合并检查，定向执行 33 条，其中一个新增用例误把无日期例外的数据声明为 DST1.1；修正输入后，该类 9 条全部通过。最终静态清单为 196 个方法，不能将其表述为最终一次完整执行 196 条。

Playwright 首次因旧日期标签定位失败 6 项；更新为当前包含默认日期提示的标签后，6 项全部通过。移动浏览器的完整桌面导出管理流原有跳过保持不变；桌面 Chrome 和 Edge 均覆盖该流程。

主要断言覆盖：通知主动确认；单选无默认值、立即保存、零分和积分合计；阻塞写入与最新输入、重复点击完成、失败保留与重试、刷新竞态；连续条件分支、未确认隐藏、选做前置跳过、必需分支阻止完成、撤销后的草稿恢复；重复每日隔离和日期例外积分；删除所选选项后的重新选择；Room 8→9 和旧备份读取；新备份替换、合并和历史得分；云端拒绝伪造选项、分值及错误分支，重复回执及新撤销阻止旧完成。

实际核心命令（在 W:\ 下运行）：

```powershell
.\gradlew.bat testProductionDebugUnitTest assembleOfflineDebug assembleProductionDebug lintOfflineRelease lintProductionDebug --no-daemon --max-workers=2
.\gradlew.bat -I build/choice-screenshots.init.gradle testProductionDebugUnitTest --tests '*ConditionalChoiceTest' --tests '*StepsImportRegressionTest' --tests '*W11RecurrenceServiceTest' --tests '*StepsBackupRegressionTest' --tests '*BackupMergerTest' --tests '*RoomBackupRepositoryTest' assembleOfflineDebug assembleProductionDebug lintOfflineRelease lintProductionDebug updateOfflineDebugScreenshotTest --no-daemon --max-workers=2
.\gradlew.bat -I build/choice-screenshots.init.gradle testProductionDebugUnitTest --tests '*ConditionalChoiceTest' assembleOfflineDebug assembleProductionDebug lintOfflineRelease lintProductionDebug updateOfflineDebugScreenshotTest --no-daemon --max-workers=2
npm --prefix web run build:all
npm --prefix web test
npm --prefix cloud run check
npm --prefix web run test:e2e
npm --prefix web run test:e2e -- --grep 'creates a draft|separates recurring'
python scripts/testing/inspect-android-profiles.py
```

Web offline/connected 均构建通过，共享协议生成文件一致性和两种网页包网络边界通过。每日清单 57、发布清单 170、完整清单 196 为静态方法数。

## 构建、Lint、截图与网络边界

`assembleOfflineDebug`、`assembleProductionDebug`、`lintOfflineRelease`、`lintProductionDebug` 均成功。两个 Lint 报告均为 0 错误、0 警告、0 信息项，见 `app/build/reports/lint-results-offlineRelease.xml`、`app/build/reports/lint-results-productionDebug.xml`。定向测试结束后仅调整 TaskDetailScreen 回调缩进及空行；再次构建两个 APK 并运行双变体截图，没有为纯空白调整重复 JVM 套件。

最终截图 offline 31 通过、0 失败、0 跳过；production 31 通过、0 失败、0 跳过。新增 6 个场景覆盖通知确认、单选无默认值、零分选项、确认前隐藏分支、确认后展开分支、深色和大字体，并已逐张目视检查。原离线 25 张基准未改变。首次直接共用离线基准时，production 有 4 张既有心情截图因“完成后管理员可见”提示差异而失败；检查实际图和差异后为这 4 张增加联网版基准，重跑通过，未放宽比对阈值。`scripts/testing/android-screenshot-matrix.init.gradle` 将公共基准和联网版覆盖合并到构建目录。

最终实际命令：

```powershell
.\gradlew.bat -I scripts/testing/android-screenshot-matrix.init.gradle assembleOfflineDebug assembleProductionDebug validateOfflineDebugScreenshotTest validateProductionDebugScreenshotTest --no-daemon --max-workers=2
.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --offline --no-daemon --max-workers=2
git diff --check
```

最终构建及截图日志：`build/choice-delivery-final.log`（BUILD SUCCESSFUL，1 分 54 秒）。截图 XML 留存为 `build/choice-screenshot-offline-results.xml` 和 `build/choice-screenshot-production-results.xml`。最后修复的 9 条定向用例 XML 另存为 `build/choice-final-focused-results/TEST-com.ds.localtaskmanager.data.ConditionalChoiceTest.xml.retry-passed`，首次失败记录仍保留。

按 `scripts/ci/check-connectivity-boundaries.sh` 同样规则检查 offlineRelease 合并 Manifest，不含 INTERNET / ACCESS_NETWORK_STATE 权限；依赖报告不含 okhttp / retrofit / ktor-client。依赖任务成功，断言通过；证据为 `build/choice-offline-dependencies.log` 和 `build/choice-offline-boundary.json`。`git diff --check` 通过。

## 同源 Debug APK

两包均从上述工作区源码和现有变体配置构建；使用构建后的实际文件计算 SHA-256。

| 变体 | 环境 / 包名 | 大小（字节） |
| --- | --- | ---: |
| offline Debug | 无联网环境 / `com.ds.localtaskmanager.debug` | 12473985 |
| production Debug | `https://api.rochelimit.me` / `com.ds.localtaskmanager.connected.production.debug` | 20380324 |

- 离线版：[app-offline-debug.apk](../app/build/outputs/apk/offline/debug/app-offline-debug.apk)，实际路径 `D:\Projects\local-task-manager\app\build\outputs\apk\offline\debug\app-offline-debug.apk`；版本 `0.1.0-alpha.10-debug`，versionCode 11。SHA-256：`29d617ccbe083209a1c8825e29838a05b266af050fabd14d9298fe8ba111b6f8`。
- 联网版：[app-production-debug.apk](../app/build/outputs/apk/production/debug/app-production-debug.apk)，实际路径 `D:\Projects\local-task-manager\app\build\outputs\apk\production\debug\app-production-debug.apk`；版本 `0.1.0-alpha.14-executor-debug`，versionCode 15。SHA-256：`978c520e5e1db1a5956bf93a8c658359291c1917363acbfbddf7522d4dafe907`。

机器可读校验记录为 `build/choice-apk-hashes.json`，纯文本为 `build/choice-apk-sha256.txt`。APK 是本地构建产物，未发布到 GitHub。

## 交付限制

本轮使用本地数据库、Robolectric、Compose 自动截图和浏览器测试；没有真机安装、设备交互或云端正式部署。production Debug 包仍指向 `https://api.rochelimit.me`，新增跨端功能所需云端实现仍在本地，须后续部署相应云端代码才能在正式服务使用。
