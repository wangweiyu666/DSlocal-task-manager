# 验证与交付记录

本页按验证批次归档，保留当时源码、命令、实际数量、失败修复、产物校验值及覆盖限制。文中的“当前”“本轮”“尚未部署”和旧变体名称均指对应批次，不表示现在的发布状态，也不构成重跑指令。日常规则只见 [最小测试方案](minimal-testing.md)，部署只见 [生产运行手册](stage4-production-runbook.md)。

| 日期 | 记录 |
| --- | --- |
| 2026-10-02 | [任务自定义提醒](#custom-reminder-2026-10-02) |
| 2026-10-02 | [每小时后台同步](#hourly-sync-2026-10-02) |
| 2026-09-13 | [Android 启动优化](#startup-2026-09-13) |
| 2026-09-12 | [流程简化](#workflow-2026-09-12) |
| 2026-09-12 | [通知、单选积分与条件步骤](#choice-2026-09-12) |
| 2026-09-12 | [信息告知自动保存](#autosave-2026-09-12) |
| 2026-09-09 | [后台同步真机对照与轮询修复](#background-2026-09-09) |
| 2026-09-07 | [正式域名切换与变体验证](#production-2026-09-07) |
| 2026-09-06 | [日期与分步骤](#steps-2026-09-06) |
| 2026-09-06 | [心情记录](#mood-2026-09-06) |


<a id="custom-reminder-2026-10-02"></a>

## 2026-10-02 任务自定义提醒

基线 `8583c7e8d4dbbe0d1144ce8be46bcf523864ec4c` 加未提交修改；保留每小时同步、应用更名及无关 artifacts。实现协议 `a`、网页配置/日期例外、Android 提醒共存与同刻去重、Room 9→10 和 DSTB1 payload 6（读取 1～6）。本次未提交、推送、部署、发布或安装。

环境：Node 24.19.0、JDK 17.0.19、adb 37.0.0、Gradle 8.9。Android 使用 W:/G: 临时路径映射和本地 Robolectric Android 15 缓存；截图使用 Asia/Hong_Kong、zh_CN、UTF-8。命令均在仓库根目录执行；Gradle 使用 `--no-daemon`。

| 实际命令 / 检查 | 结果 |
| --- | --- |
| `.\gradlew.bat compileProductionDebugKotlin` | 通过 |
| `.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release` | 首次测试编译失败，修正测试夹具 DAO 调用后执行 45 项：44 通过、1 失败、0 跳过；失败为旧 payload 5 断言 |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*ConditionalChoiceTest.backup v6*'`（同轮请求构建/Lint/截图） | 1 项失败，发现 metadata 版本仍为 5；后续任务未计为通过 |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*ConditionalChoiceTest.backup v6*' --tests '*CustomReminderTest.backupRoundtrip*' assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug processOfflineReleaseMainManifest -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest` | 修复 metadata 后 2 项通过；双构建通过、双 Lint 0 问题、production 截图 31 通过/0 失败/0 跳过 |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*CustomReminderTest.simultaneousTemplate*' --tests '*CustomReminderTest.recurringTaskDays*' assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug` | 2 项通过；修复同批模板与例外更新按旧截止时间校验的问题，双构建/Lint 通过 |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*CustomReminderTest.simultaneousTemplate*' assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug` | 最后 1 项通过，补证模板更新保留日期例外的截止前提醒；双构建通过，双 Lint 0 问题 |
| `npm --prefix web run typecheck`、`npm --prefix web run test:minimum` | 通过；12 项通过、0 失败 |
| `npm --prefix cloud run typecheck`、`npm --prefix cloud run test:minimum` | 通过；3 项通过、0 失败、11 项因筛选跳过 |
| `npm --prefix web run build:all`、`npm --prefix web run verify:protocol` | 通过：生成协议一致、双环境构建和包边界通过 |
| `npm --prefix web run test:connected-smoke` | 1 项通过；生产构建、模拟服务，非线上验收 |
| `.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath`、清单和 APK 检查 | offline 无网络依赖/权限；两包名称 DStationery，`apksigner verify` 均通过 |
| 本地 `dist-offline` 浏览器验收 | 截止前提醒与自定义时间共存；重复任务 02:00 刷新恢复；390px 窄屏无重叠；控制台无错误 |

按每个用例最后一次结果，production 活动集合 **46 个不同用例通过、0 失败、0 跳过**；这是一次活动运行加修复后的定向复测，并非重新运行完整集合。原始阶段 XML、逐用例结果、源码文件 SHA-256 和 APK 校验值在 `build/custom-reminder-verification/results.json`。对应日志为 `build/custom-reminder-{compile,tests,tests2,final,final2,import-final,coexistence-final,web-build,offline-dependencies}.log`；本地网页截图为同证据目录下 `web-coexisting.png`、`web-recurring-mobile.png`。

覆盖无截止提醒、共存/同刻并发去重、凌晨分界、日期例外覆盖/清除/继承、延期、完成/撤销、删除、错过时间、权限关闭、有效截止关系、Room 迁移和新旧备份/已发送记录合并。未运行稳定旧回归全套、offline 截图或真机系统调度；31 张 production 截图为本地 Compose 验证，未访问线上。末两次仅修改导入/校验逻辑，复用此前渲染证据。通知沿用系统尽力调度，未实测 Doze、厂商策略、真实时区切换或分钟级送达。

最终 Debug APK（以上最后构建产生，附邻接 `.apk.sha256`）：

- offline：`D:\Projects\local-task-manager\app\build\outputs\apk\offline\debug\app-offline-debug.apk`；SHA-256 `0806dbcb4ae02f12cdf03238f3523e3bfeefba0e81d430ba8c484a8db79ec7a8`。
- production：`D:\Projects\local-task-manager\app\build\outputs\apk\production\debug\app-production-debug.apk`；SHA-256 `3842125436ea6e378077fe4f17293b4b427a3b5ba3b07a600142fd18b0da840a`。

<a id="hourly-sync-2026-10-02"></a>

## 2026-10-02 每小时后台同步

基线 `8583c7e8d4dbbe0d1144ce8be46bcf523864ec4c` 加本次未提交修改。联网变体新增唯一的一小时周期任务，首次延迟一小时；登录与启动时登记，退出/清除会话时取消，限制解除后恢复。空上传队列仍主动拉取；身份、冷却、同步锁及退避沿用现有逻辑。未提交、推送、发布或安装；原有不相关 artifacts 保留。

验证使用 JDK 17.0.19、SDK adb 37.0.0、Windows W:/G: 临时映射及缓存 Robolectric Android 15；截图为 Asia/Hong_Kong、zh_CN、UTF-8。

| 实际命令 / 检查 | 结果 |
| --- | --- |
| `.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release --offline --no-daemon` | 38 项，37 通过、1 失败、0 跳过；失败为测试重复初始化 WorkManager |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*ConnectedSyncEngineTest.hourly*' --tests '*ConnectedSyncEngineTest.startup*' --no-daemon` | 修正夹具后 7 项，6 通过、1 失败、0 跳过；新增限制解除断言超时，该次构建已先行编译业务类，未包含随后补入的恢复登记逻辑 |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*ConnectedSyncEngineTest.hourlyRegistrationRestoresAndCancelsOnLogout' assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug processOfflineReleaseMainManifest -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest --no-daemon` | 固定源码后重编译：1 项通过、0 失败、0 跳过；双 Debug 构建、双 Lint 均通过（0 问题）；31 张 production 截图通过、0 失败、0 跳过 |
| `.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --no-daemon` + 合并清单检查 | 无 okhttp/retrofit/ktor-client；offline Debug/Release 无 INTERNET / ACCESS_NETWORK_STATE；APK 中小时调度实现仅存在于 production |

按各用例最后一次结果，活动集合 38 个不同用例全部通过；未重复其他已通过用例。新增五项覆盖周期/网络约束、唯一 UPDATE 策略、身份载荷、空队列拉取、冷却重试、启动恢复、限制解除和退出取消。另有一次离线 Gradle 插件缓存解析失败，未执行测试，联网补齐后继续。

未运行全部 205 个静态测试方法、offline 截图、设备安装或真实一小时等待。周期登记使用注入调度回调验证，未实测厂商自启动、Doze 或系统持久化时序；一小时是期望周期，不保证准点。原始各阶段 XML、日志索引、最终源码文件哈希和 APK 校验值位于 `build/hourly-sync-verification/results.json` 及同目录；构建日志为 `build/hourly-sync-final.log`。

| 同源本地 Debug APK | 环境 | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/offline/debug/app-offline-debug.apk` | none | `6837e8287e0b21353c8b3a909da9fb6eeefd0aa362a2eab02831fcd14888b398` |
| `app/build/outputs/apk/production/debug/app-production-debug.apk` | https://api.rochelimit.me | `1c5de41369d92a96cef82353bcbca2bc9febde88b330e98d4f1f6d9d88bff19f` |

<a id="startup-2026-09-13"></a>

## 2026-09-13 Android 启动优化

源码为 `1342e4c9e18b9ee5b9422d0590f6dabb1b8d7f45` 加当前未提交修改，保留此前流程和文档修改；未提交、推送、发布或安装。已有有效执行者身份和同步缓存时先显示本地任务，后台验证账号；已知账号限制不放行，确认会话失效后仍清理缓存。加密数据库初始化和清理移到后台，任务页仍在备份恢复登记后进入。今日页合并启动/恢复请求，权限变化保留后续整理，失败允许重试。

JDK `17.0.19+10`、SDK adb `37.0.0`，Windows 临时 W: 项目 / G: Gradle 缓存；Robolectric 使用本地 Android 15 缓存，截图使用 Asia/Hong_Kong、zh_CN、UTF-8。命令均为本地执行：

| 命令 / 检查 | 实际结果 |
| --- | --- |
| `.\gradlew.bat testProductionDebugUnitTest --tests '*ConnectedSyncEngineTest.startup*' --tests '*TodayStartupTest' --offline --no-daemon` | 5 通过、0 失败、0 跳过 |
| `.\gradlew.bat assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug processOfflineReleaseMainManifest -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest --offline --no-daemon` | 两份 Debug APK 构建成功；31 张 production 截图通过，0 失败、0 跳过；两变体 Lint 均通过，0 错误、0 警告 |
| `.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --offline --no-daemon`，并检查合并清单 | 通过；无 okhttp/retrofit/ktor-client，offline Debug/Release 无 INTERNET / ACCESS_NETWORK_STATE |
| `python scripts/testing/inspect-android-profiles.py` | 清单匹配；活动 33、旧回归 173、完整 200 个静态方法，不表示执行通过数 |

首轮定向运行 5 项中 2 项失败：SQLite 临时数据库无法打开，及失效会话测试缺少 WorkManager 初始化。缩短新测试名称、补齐 WorkManager 初始化后重跑；同时增加整理期间权限变化不丢失的断言，最终 5 项全通过。首次日志和 XML 保存在 `build/startup-verification/attempt1/`，最终结果和启动修改文件哈希在 `build/startup-verification/results.json`，构建日志为 `build/startup-build-validation.log`。

未运行完整单元集合、offline 截图、设备安装或真机冷启动计时；31 张截图覆盖现有 production 预览，不覆盖进程启动与真实云端。单元环境替代 SQLCipher，不能用本轮结果声称具体提速秒数或倍数。后台启动状态的 UI 和真实 Keystore 路径仍需设备验收。

同一工作区源码的本地 Debug 产物（未发布，版本号未递增）：

| 环境 / 路径 | SHA-256 |
| --- | --- |
| offline，无 API；`app/build/outputs/apk/offline/debug/app-offline-debug.apk` | `bed4dd610b8104020aba7a8aeebc5afcca73be90481bdf15d1e0e2894dc01cb4` |
| production，`https://api.rochelimit.me`；`app/build/outputs/apk/production/debug/app-production-debug.apk` | `b5ce6b3717b0e49eb7a2534e9d31f4d5d61fcb3d052499dc9d74b9b8787048ec` |

<a id="workflow-2026-09-12"></a>

## 2026-09-12 流程简化

源码基线：`1342e4c9e18b9ee5b9422d0590f6dabb1b8d7f45`，加本轮未提交的 CI、测试清单、发布准备脚本及文档修改。保留此前未提交文档。本次没有提交、推送、部署、发布 APK 或设备安装。

### 实际执行

| 命令 / 检查 | 结果 |
| --- | --- |
| `npm --prefix web run test:minimum` | 2 个文件，7 通过，0 失败，0 跳过 |
| `npm --prefix cloud run test:minimum` | 1 个文件，2 通过，0 失败，11 因名称筛选跳过 |
| `node --test scripts/ci/verification-reuse.test.mjs` | 最终 4 通过，0 失败，0 跳过；最初 3 条通过后补充 dispatch 门禁场景；最后补充最新失败禁止回退旧成功记录，共执行三次，仅后续检查新增改动 |
| `actionlint -shellcheck= -pyflakes= .github/workflows/android-ci.yml .github/workflows/cloud-ci.yml .github/workflows/dom-web-pages.yml` | actionlint 1.7.12，3 个工作流无错误；另有最后参数调整后的复核 |
| PowerShell AST：两个 prepare 脚本、get-android-evidence.ps1 | 3 个脚本无语法错误 |
| `bash -n scripts/ci/select-android-profile.sh` | 通过 |
| Android 命令选择，临时 Gradle 替身 | 默认最小集合、无原因拒绝完整集合、有故障原因双变体完整集合，3 个场景通过；没有调用真实 Gradle |
| Web 产物临时夹具 | stamp/check 同 SHA 接受、不同 SHA 拒绝；不是实际下载/部署 |
| `python scripts/testing/inspect-android-profiles.py` | 清单全部匹配；daily/release 28、regression 170、full 196 个静态方法 |
| `git diff --check` | 通过 |

Node 24.19.0。actionlint 下载首次受沙箱 TLS 限制失败，在正常用户环境从官方 release 下载后验证成功。系统没有 PyYAML，最终使用 actionlint 校验 YAML/Actions；未安装 Python 包。shellcheck/pyflakes 未安装，actionlint 显式关闭这两个外部检查，新增 Bash 脚本另做语法和选择验证。

### 范围限制

本轮是流程与选择规则修改，未重跑 Android 产品测试、完整 Web/Cloud 套件、截图、构建或 Lint，也未生成新 APK。28 是静态方法数，不能记为 28 条运行通过。13 条最终单元用例通过（Web 7、Cloud 2、复用逻辑 4），Cloud 11 条被筛选未执行。

新的跨工作流 artifact 下载、受保护部署和 CI 复用尚未在 GitHub Actions 远端运行；本地语法与逻辑检查不代表已线上验证。推送后由新规则运行一次适用最小 CI，部署需用户当次授权。公开签名、受保护环境与设备豁免证据规则继续有效。

<a id="choice-2026-09-12"></a>

## 2026-09-12 通知、单选积分与条件步骤

### 源码和范围

基于 `882489f008ce44104292249ee58ef2d67437eac9` 的未提交工作区。保留本轮之前的信息告知自动保存修复和既有文档修改。本次不提交、不推送、不部署、不发布、不安装到设备。

最终应用源码指纹 SHA-256：`c36584206dc8935a7821d15cef20d8b7a5e77498b89795016ca4c0a199ae7454`。按相对路径排序，对 `app/src/main`、`app/src/offline`、`app/src/connected`、`app/src/production`、`app/schemas`、`shared/protocol`、`web/src`、`cloud/src` 及仓库存在的 Gradle 根配置逐项计算“路径、NUL、原始文件字节、NUL”，共 215 个文件；详情见本地 `build/choice-source-state.json`。它描述工作区内容，不是 Git 提交 SHA。

### 运行环境

Windows，JDK 17.0.19，Android SDK adb 37.0.0，Node 24.19.0。Gradle 项目和缓存分别使用临时 `W:`、`G:` 短路径，`GRADLE_USER_HOME=G:\`。Robolectric 使用本地 Android 15 instrumented 依赖，JVM 时区 Asia/Hong_Kong；截图另设置语言 zh、国家 CN、UTF-8。

Windows 路径切换使部分旧 Kotlin 增量缓存失效，Gradle 自动回退到非增量编译后完成，没有降低测试断言。

### 单元与浏览器验证

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

### 构建、Lint、截图与网络边界

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

### 同源 Debug APK

两包均从上述工作区源码和现有变体配置构建；使用构建后的实际文件计算 SHA-256。

| 变体 | 环境 / 包名 | 大小（字节） |
| --- | --- | ---: |
| offline Debug | 无联网环境 / `com.ds.localtaskmanager.debug` | 12473985 |
| production Debug | `https://api.rochelimit.me` / `com.ds.localtaskmanager.connected.production.debug` | 20380324 |

- 离线版：[app-offline-debug.apk](../app/build/outputs/apk/offline/debug/app-offline-debug.apk)，实际路径 `D:\Projects\local-task-manager\app\build\outputs\apk\offline\debug\app-offline-debug.apk`；版本 `0.1.0-alpha.10-debug`，versionCode 11。SHA-256：`29d617ccbe083209a1c8825e29838a05b266af050fabd14d9298fe8ba111b6f8`。
- 联网版：[app-production-debug.apk](../app/build/outputs/apk/production/debug/app-production-debug.apk)，实际路径 `D:\Projects\local-task-manager\app\build\outputs\apk\production\debug\app-production-debug.apk`；版本 `0.1.0-alpha.14-executor-debug`，versionCode 15。SHA-256：`978c520e5e1db1a5956bf93a8c658359291c1917363acbfbddf7522d4dafe907`。

机器可读校验记录为 `build/choice-apk-hashes.json`，纯文本为 `build/choice-apk-sha256.txt`。APK 是本地构建产物，未发布到 GitHub。

### 交付限制

本轮使用本地数据库、Robolectric、Compose 自动截图和浏览器测试；没有真机安装、设备交互或云端正式部署。production Debug 包仍指向 `https://api.rochelimit.me`，新增跨端功能所需云端实现仍在本地，须后续部署相应云端代码才能在正式服务使用。

<a id="autosave-2026-09-12"></a>

## 2026-09-12 信息告知自动保存

源码基于 `882489f008ce44104292249ee58ef2d67437eac9`，验证对象为本次未提交的工作区代码修改。开始时已有 AGENTS.md 和其他文档修改，本次保留这些修改。未推送、部署或安装到设备。

### 行为与验收

- 正文停止输入 500ms 后自动保存，页面显示保存状态，失败时保留正文并提供重试。
- 点击完成时先保存最新正文，等待正在进行的写入结束；成功后才完成，失败时保持未完成。完成期间及完成后禁止编辑正文。
- 返回、切到后台、复制和分享前保存最新正文；保存与刷新期间的新输入不会被旧快照覆盖。
- 清空正文能够持久化；空正文仍不能完成任务。保留 2000 Unicode 码点限制、完成锁定和撤销恢复规则。

### 实际结果

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

### 命令与环境

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

### 同源 Debug APK

构建后未修改应用源码；仅定向测试，不属于完整矩阵交付。两个 APK 均未安装或发布。

- offline：`app/build/outputs/apk/offline/debug/app-offline-debug.apk`，离线环境，包名 `com.ds.localtaskmanager.debug`，版本 `0.1.0-alpha.10-debug`（11）。SHA-256：`A874E1F6E3FEED6952B6687A0467B304AF198670AB70A394722E96B5A7160280`。
- production：`app/build/outputs/apk/production/debug/app-production-debug.apk`，正式 API 环境，包名 `com.ds.localtaskmanager.connected.production.debug`，版本 `0.1.0-alpha.14-executor-debug`（15）。SHA-256：`F70D036F0EE80443418092179172E9DAF3E8883E9F58EB53A51A98D061520AA1`。

<a id="background-2026-09-09"></a>

## 2026-09-09 后台同步真机对照与轮询修复

### 结论

本次小米 Android 15 真机的阻塞原因是系统拒绝后台自启动。完成回调、待上传记录和 WorkManager 登记正常；关闭自启动时，结束应用后台进程后恢复网络，系统拒绝启动 `SystemJobService`。临时开启自启动后，同样的操作由系统启动新进程，并在约 12 秒内收到上传确认。

验证仅使用已授权的测试执行者和专用零积分任务。没有触碰其他执行者结果，没有清除应用数据，没有使用 force-stop 或手动同步代替后台验收。

### 证据时间线（Asia/Hong_Kong）

| 场景 | 观察 |
| --- | --- |
| 21:13:59.523 离线完成 | `COMPLETE_COMMITTED`、`MUTATION_CALLBACK` 同时出现；21:13:59.530 写入待上传命令；21:13:59.591 `WORK_REGISTERED`，总计约 68ms |
| 21:14:49 恢复 Wi-Fi | 应用后台进程已结束；约六分钟内没有新进程或云端结果 |
| 21:21 重新打开应用 | 原先登记的 Worker 完成，云端随后显示结果；只算前台恢复，不算独立后台通过 |
| 第二次关闭自启动的对照 | 结束进程前后，系统仍保留等待联网的任务；恢复网络后任务无法启动 |
| 21:25:28.495 系统原因 | `AutoStartManagerService: MIUILOG- Reject service`，目标为本应用的 `androidx.work.impl.background.systemjob.SystemJobService` |
| 21:25:28.497 系统结果 | JobScheduler 对已经 `NET READY` 的任务报告 `Error executing JobStatus` |
| 临时开启自启动后的对照 | 再次撤销、等待撤销上传、断网完成、回桌面、结束后台进程并确认进程不存在，然后恢复 Wi-Fi |
| 21:32:12.391 | 系统启动新进程，出现 `PROCESS_RECOVERY` |
| 21:32:12.429 | 新进程执行 `WORK_START`；后续同步入口为 `ATTEMPT WORKER` |
| 21:32:22.092 / 21:32:24.335 | `UPLOAD_START` / `UPLOAD_ACK`，同一命令引用；本轮新进程没有 `ATTEMPT FOREGROUND`、`NETWORK_CALLBACK` 或 `NOTIFICATION_POLL` |
| 21:32:25.898 | Worker 返回 `COMPLETE`，后续链中任务检查空队列后成功退出 |
| 管理员只读刷新 | 最新 21:31 完成记录成为当前正式结果；旧撤销和完成记录保留为历史 |

### 环境与恢复

安装包为前轮构建的 production 诊断 Release APK，基于 `c60cf3ff1e67694b1b34d280575536f842e43269` 加本地诊断改动，`syncDiagnostics=true`，版本 `0.1.0-alpha.13-executor` / 14。签名沿用正式执行者包；这不是 GitHub Release 中的普通 APK。原始设备日志保存在本机忽略目录，不加入发布附件。

本轮未重新构建 APK。测试后已验证：自启动恢复关闭，Wi-Fi 恢复开启，移动数据维持关闭。诊断版和测试数据保留。系统设置恢复后，不应声称该手机仍能在同样的进程结束场景下独立后台同步。

此结论仅覆盖这台小米 Android 15 的对照场景，不代替 API 26/33、其他厂商、重启以及全部发布设备门禁。现有 GitHub Releases 保持草稿；不因单项后台场景通过而自动公开发布。
## 前后台轮询修复补充（本地源码）

通知轮询改由 `ConnectivityContent` 的 `repeatOnLifecycle(STARTED)` 管理：页面不可见时取消循环、网络回调及页面数据观察，重新可见时恢复单个循环。已发出的网络请求可能完成收尾；取消后不再由该轮询触发新的同步。Application 级 WorkManager 的持久化补传与重试保持独立。

本次在 `c60cf3f` 基础的未提交工作区验证，命令：

```powershell
.\gradlew.bat testProductionDebugUnitTest --tests com.ds.localtaskmanager.connected.ConnectedSyncEngineTest --tests com.ds.localtaskmanager.connected.ConnectedRuntimeTest --tests com.ds.localtaskmanager.connected.ConnectedSyncCoordinatorTest --offline --no-daemon
```

使用本机 Gradle/Android 缓存及离线 Robolectric 依赖。最终 23 条通过，失败 0、错误 0、跳过 0。新增测试覆盖三轮前后台切换、15 秒边界、后台不轮询、恢复不重复和后台执行入口可用。过程中修正了显式返回类型编译错误、新测试的缺失游标与协程测试线程等待方式；未跳过或弱化断言。通过后仅调整缩进与文档。

本修复尚未构建、安装或完成新的真机验证；下文的真机结果属于修复前诊断 APK，不能视为新版设备验收。

<a id="production-2026-09-07"></a>

## 2026-09-07 正式域名切换与变体验证

### 域名切换完成记录（2026-09-07）

- 固定提交：`ca776ed1ac2d69510069ca19638f41d17f6a75f4`，已推送 main；[Cloud CI](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/34133433080) 和 [Android CI](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/34133433030) 均成功。
- [正式发布](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/34134020166) 的 verify、deploy-production 均成功。生产管理域名现为 `prod.rochelimit.me`；API 保持 `api.rochelimit.me`。API 的允许来源及 API/Web 的 Access audience 已同步切换，其他 secrets 保留。
- 迁移前已在本机忽略目录保存两个生产 Worker 的部署版本、两个生产 D1 的 Time Travel bookmark、旧域名映射及 Access 恢复配置。未执行数据库恢复或删除。
- 真实管理员验收通过后已解除 `test.rochelimit.me`、`api-staging.rochelimit.me`；旧 `staging.rochelimit.me` 已随部署解除。API 读回确认 Worker 自定义域仅剩 `prod.rochelimit.me` 和 `api.rochelimit.me`，均绑定对应 production Worker。
- 两个 staging Worker 的 cron 均为空，workers.dev 与 preview URLs 均关闭。读回确认 `dstationery-staging` 和 `dstationery-deletion-ledger-staging` 两个 D1 仍存在；Worker 与旧 Access 配置保留供回退。未合并、恢复或删除业务数据库。
- 匿名门禁检查通过；用户完成 Access 登录后，主代理在真实管理员会话中确认任务库显示 5 项任务、状态为“已联网”。刷新后安全会话恢复成功，同样 5 项任务正常显示，两次控制台 error 检查均为空。验收仅查看和刷新，未修改业务数据。
- 本次无 GitHub APK Release。下面独立交付来自上述固定提交，不包含工作树中尚未提交的 WorkManager 功能。

| Debug APK | 来源及环境 | SHA-256 |
| --- | --- | --- |
| `build/delivery-ca776ed/offline/app-offline-debug.apk` | 同提交 Android CI artifact；离线 | `6A633EF8878B77465E5238FA175716C7FBD334251A75FEFB9FA1EACC310E6699` |
| `build/delivery-ca776ed/production/app-production-debug.apk` | `git archive` 独立源码，JDK 17.0.19，`assembleProductionDebug --offline --no-daemon` 成功；正式 API | `C8FC283CDD1673C33B87CD616188D13961F5DD79ED14E06E347D8CE592A6C937` |

### 切换前记录

切换过程中的临时域名、OAuth/Access 配置与重复进展已归入 [压缩前记录](https://github.com/wangweiyu666/DSlocal-task-manager/blob/882489f008ce44104292249ee58ef2d67437eac9/docs/production-only.md)。停用旧环境同时解除入口与 cron，保留 D1 和回退配置；配置删除不等于远程资源退役。

### Android flavor 验证

以下为包含尚未提交的 Android 后台同步实现的工作树验证与 APK，不是仅包含域名迁移提交的产物；发布提交的 CI 与对应 APK 应另行记录，不能混用。

- 环境：JDK 17.0.19、ADB 35.0.2。
- `testProductionDebugUnitTest`：使用缓存 Robolectric 运行包及 `--offline`，173 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESSFUL`。
- `testOfflineDebugUnitTest`：使用同一离线缓存配置，145 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESSFUL`。
- `lintOfflineRelease lintProductionDebug assembleOfflineDebug assembleProductionDebug --no-daemon`：`BUILD SUCCESSFUL`，两变体 APK 与 Lint 均完成。
- 离线边界：`.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --no-daemon` 输出无 `okhttp`、`retrofit`、`ktor-client`；offlineRelease 合并 Manifest 无 `INTERNET` 或 `ACCESS_NETWORK_STATE` 权限，仅保留通知、启动、振动、唤醒锁、前台服务及动态接收器权限。
- APK：`app/build/outputs/apk/offline/debug/app-offline-debug.apk` SHA-256 `37A11590B8C38351FEEDA1B75C6369FC207913217FB16C2ED3E6C7F5140FA396`；`app/build/outputs/apk/production/debug/app-production-debug.apk` SHA-256 `546B318E358743DB4C0C6CFD5C0BCCC2B2060D23BF23A5AE174866F402C96078`。
- 测试使用本机缓存 Robolectric 依赖；未进行设备安装或生产环境操作。Android 单元测试结果不替代真实设备生命周期验证。

<a id="steps-2026-09-06"></a>

## 2026-09-06 日期与分步骤

日期：2026-09-06

这份记录汇总当前已执行的 Web、Cloud 和 Android 验证证据。Android 验证使用当前工作树（基于 c67dbd72823e5ccd99d31e689d2aa8a954c2e5f0 的未提交变更，未创建新提交）；Cloud 代码本次未部署。

### Web

在仓库根目录执行：

```text
npm --prefix web test
npm --prefix web run build:all
git diff --check -- web shared docs
```

结果：

- Web 测试：10 个测试文件、78 个测试通过，失败 0，跳过 0。
- `build:all`：协议生成校验、TypeScript typecheck、offline/connected Vite 构建和 bundle boundary 检查全部通过。
- `git diff --check`：通过。

冻结后的稳定 ID 删除回归再次执行：

```text
npm --prefix web test -- --run tests/mood-task-editor.test.tsx tests/task-logic.test.ts
npm --prefix web run typecheck
```

结果：2 个测试文件、15 个测试通过；typecheck 通过。

覆盖内容包括 DST1/DST1.1 校验、STEPS 例外继承与切出、稳定步骤 ID、结果快照名称、模板与备份、预览跨午夜冻结、IndexedDB 提交回滚重试，以及任务/例外编辑器。

预览和日期语义见 [preview.md](preview.md) 与 [date-steps-implementation-plan.md](preview.md#日期截止与重复)。备份 v4 的整组步骤恢复和 Room 8 不转换旧定义约定见 [dstb1-format.md](dstb1-format.md)。

### Cloud

Cloud 最近一次已执行验证：

```text
npm --prefix cloud run typecheck
npm --prefix cloud test
```

结果：typecheck 通过；7 个测试文件、36 个测试通过，失败 0，跳过 0。Cloud 在该验证后未继续修改，因此本次 Web-only 验证没有重复运行 Cloud 套件。

步骤 Cloud 变更另完成了一次隔离本地 Worker smoke：使用专用 `.wrangler/steps-smoke` 持久化目录，将主库和删除账本的全部本地 migrations（包含 `0007_atomic_sync_commands.sql`）应用到同一状态，migration 版本为 7；随后用该状态启动本地 Worker 并运行 `cloud/scripts/smoke-local.mjs`。结果：本地 HTTP smoke 通过，测试后已停止该 Worker；未连接 production 或 staging。

### 浏览器人工验收

主代理已在 390×844 视口验收：步骤卡片无横向溢出；计时/计数步骤的顺序、参数和重排保持；保存后日期回填；任务库恢复已保存的 timer 10 与 counter 3 定义后参数保持一致。该证据属于人工浏览器检查，不替代自动化测试。

### Android

执行环境：Windows、JDK 17、Android SDK；Gradle 使用用户缓存目录 `$env:USERPROFILE/.gradle`，所有 Gradle 验证均使用 `--no-daemon --offline`。首次普通沙箱运行因 Robolectric `MavenArtifactFetcher` 的缓存访问 `SocketException` 导致 83 个统一环境失败；随后使用相同用户缓存和提升的本地访问重跑，未修改断言或跳过测试。

执行的 Gradle 任务与参数如下；`gradle.bat` 指向本机已发现的缓存 Gradle，省略当前进程 PATH 设置，发现方式见 [Windows 工具指南](windows-tooling.md)。

```text
gradle.bat :app:testConnectedDebugUnitTest --no-daemon --offline --stacktrace
gradle.bat :app:testConnectedDebugUnitTest --tests com.ds.localtaskmanager.data.StepsImportRegressionTest --no-daemon --offline
gradle.bat :app:assembleOfflineDebug :app:assembleConnectedDebug :app:assembleProductionDebug :app:lintOfflineRelease :app:lintConnectedDebug :app:lintProductionDebug --no-daemon --offline
bash scripts/ci/check-connectivity-boundaries.sh
gradle.bat :app:compileOfflineDebugAndroidTestKotlin --no-daemon --offline
```

结果：联网 Debug 单元测试 38 个 XML 类、158/158 通过，失败 0、错误 0、跳过 0。完整 XML 与 HTML 报告保存在 `app/build/reports/mood/full-connected-158-20260906/`；最新 Import focused 测试 5/5 通过。三 Debug 构建、三 lint、offline connectivity boundary 和 `compileOfflineDebugAndroidTestKotlin` 均成功。首次 AndroidTest 编译发现 `StepsSectionTest` 使用不存在的 `assertDoesNotExist` 扩展，UI 测试 owner 修正后重新编译通过。

APK 产物（均为 Debug，包名分别为 `com.ds.localtaskmanager.debug`、`com.ds.localtaskmanager.connected.debug`、`com.ds.localtaskmanager.connected.production.debug`）：

| 环境 | 绝对路径 | SHA-256 |
| --- | --- | --- |
| offline（无 API） | `D:/Projects/local-task-manager/app/build/outputs/apk/offline/debug/app-offline-debug.apk` | `7177039215D34D399AE3E25F41C1755FF60819A1D34700D6355FBAF99D3580E4` |
| connected（staging，`api-staging.rochelimit.me`） | `D:/Projects/local-task-manager/app/build/outputs/apk/connected/debug/app-connected-debug.apk` | `2F7C40656DC4988A412D83FE88C9E25D9C5B41E99569CDFE6C19465519166B6D` |
| production（`api.rochelimit.me`） | `D:/Projects/local-task-manager/app/build/outputs/apk/production/debug/app-production-debug.apk` | `32D68F8F484A876964E713EF85BB17B9DEDBD8F2D7E7C1367251DCE4A39FF513` |

当前没有连接设备或可用 AVD，因此未执行 Android 设备上的安装、视觉、读屏、键盘/IME 或真实账号登录/离线同步验收。Cloud 生产环境本次也未部署；联网 APK 的 production API 配置不代表服务端步骤能力已发布。

### 待验证

- Android 设备视觉、读屏、IME、真实账号登录和离线同步：当前无设备/AVD，未执行。
- Cloud production 步骤能力部署与三端联调：本次未部署 Cloud production；本地 HTTP smoke 已在上文单独记录。

<a id="mood-2026-09-06"></a>

## 2026-09-06 心情记录

验证代码为 Git 基线 `8c5124fdb648d5319af4bc3b1abce2b23b160bb6` 加本次未提交的心情功能改动。未推送、部署或发布。测试由测试代理执行，主代理审查新增断言、实际渲染图和最终测试 XML，并独立复核 APK SHA-256。

### 最终检查

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

### 验证范围

- 五档实际触摸点选、拖动吸附、初始未选择、无障碍状态与逐档 `SetProgress`、只读答案。键盘用 OS IME 可见性断言确认已弹出，再验证完成按钮仍可见。
- 浅色、深色、1.6 倍字体及完整任务详情渲染；主代理检查新增预览。
- 自动保存、失败后重试、保存期间继续编辑、完成前保存顺序、返回期间新输入、完成锁定、撤销后保留答案。
- 每日实例隔离、单日执行类型切换清理、固定积分、Room 升级、旧备份解码、心情快照替换与合并；较新的撤销状态继续遵循现有实例合并规则。
- 协议往返及非法参数，Web 任务／模板备份和单日调整编辑；联网完成／撤销／重新完成、重复与延迟事件、管理员结果选择及单日执行类型解析。权限和多执行者隔离沿用既有测试及本地 HTTP smoke。

设备交互验证使用 API 35 模拟器；未执行实体设备触感体验或人工 TalkBack 朗读验收。联网后端改动仅在本地验证，尚未部署到下列远程环境。

### Debug APK

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

<a id="initial-release"></a>

## 首版设备验收（历史）

`v0.1.0-alpha.1` 发布前完成 JVM 97/97、Preview 截图 18/18、API 35 仪器测试 23/23 与 API 26 W31 关键流程 3/3；发布证据覆盖 API 26、33、35 和 Android 13+ 真机。最终发布 CI 见 [GitHub Actions 31285405667](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/31285405667)。

<a id="background-initial"></a>

## 后台补偿初期实现与 2026-09-07 安装（历史）

以下记录为当时未提交工作树、旧 connected/staging 包；安装启动不代表正式后台调度通过。


| 验证层级 | 实际命令／设备 | 结果 | 未覆盖范围 |
| --- | --- | --- | --- |
| 最终 JVM targeted | `.\gradlew.bat testConnectedDebugUnitTest --tests com.ds.localtaskmanager.connected.ConnectedSyncEngineTest --tests com.ds.localtaskmanager.connected.ConnectedSyncCoordinatorTest --no-daemon` | Worker 映射修正后：10 tests / 0 failures / 0 errors / 0 skipped；不重复已通过的完整套件 | 真实系统 WorkManager 调度 |
| connected 完整单元套件 | `.\gradlew.bat testConnectedDebugUnitTest --no-daemon` | 通过：41 suites / 173 tests / 0 failures / 0 errors / 0 skipped | 真实设备生命周期 |
| 三变体构建与 Lint | `.\gradlew.bat assembleOfflineDebug assembleConnectedDebug assembleProductionDebug lintOfflineRelease lintConnectedDebug lintProductionDebug --no-daemon`；联网 Worker 修正后重建 `assembleConnectedDebug assembleProductionDebug lintConnectedDebug lintProductionDebug --no-daemon` | 两次均 BUILD SUCCESSFUL；Lint 0 errors / 0 warnings。离线边界：offlineRelease 合并 Manifest 无 INTERNET/ACCESS_NETWORK_STATE，offlineReleaseRuntimeClasspath 未发现联网 client 依赖（`.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --no-daemon`） | 设备后台回收 |
| APK 交付 | `app/build/outputs/apk/.../debug/*.apk`；源码工作区基于 HEAD `a259733`（未提交工作树） | offline Debug SHA-256 `2C3E9A2C3F7D3F5726453F0E9967AC00DA9674E7CD0DC2FDA7973A46C2B8F6C8`；connected Debug（Worker 修正后）`D5FA78F974C5E24D968E0E21257DE3FAA00B5292490DCE113982DF81EDF98A44`；production Debug（Worker 修正后）`3144F29374EF9D085C9E806016A82333C1DFD3F36835E083A3979B27BC2B3762` | 未发布、未安装 |
| 真实设备安装启动 | 2026-09-07；ADB serial `9b7739b2`，型号 `23049RAD8C`，Android 15/API 35；`adb install -r` connected Debug 后启动 `com.ds.localtaskmanager.connected.debug` | 通过安装、启动；版本 `0.1.0-alpha.10-connected-debug` / code 11；已核对 APK SHA-256 为 `D5FA78F974C5E24D968E0E21257DE3FAA00B5292490DCE113982DF81EDF98A44` | 尚未登录测试账号；无专用任务；无 Worker 执行证据 |
| 真实后台场景 | 同一设备 | 未验证 | 断网、回收、取消、强停后重开；需测试账号和专用任务 |

补充：早期测试曾暴露 Robolectric Room invalidation cleanup 和 WAL SQLite fixture 问题，已通过测试 teardown 关闭主数据库及测试专用 TRUNCATE journal 修正；没有吞掉 teardown 异常，也未改变生产 journal 配置。设备已连接，但当前停留在登录页；没有 Worker 日志或 WorkManager 执行证据，因此不把安装启动结果计为后台调度通过。


### 本地 Debug APK

构建环境：JDK 17.0.19，ADB 35.0.2；三个包沿用现有版本配置。

| 变体 | 服务环境 | APK 路径 |
| --- | --- | --- |
| offline | 离线，无云端地址 | `app/build/outputs/apk/offline/debug/app-offline-debug.apk` |
| connected | staging：`https://api-staging.rochelimit.me` | `app/build/outputs/apk/connected/debug/app-connected-debug.apk` |
| production | production：`https://api.rochelimit.me` | `app/build/outputs/apk/production/debug/app-production-debug.apk` |

## 2026-10-02 Android 应用名称统一

基线 8583c7e8d4dbbe0d1144ce8be46bcf523864ec4c 加未提交的每小时同步及本次名称修改。production 和 connected 的 app_name 统一为 DStationery；main 原已相同。

执行命令：`.gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release assembleOfflineDebug assembleProductionDebug lintOfflineDebug lintProductionDebug processOfflineReleaseMainManifest -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest --no-daemon`。沿用上节 JDK、缓存和截图环境；构建成功。活动单元测试 38 通过、0 失败、0 跳过；production 截图 31 通过、0 失败、0 跳过；双变体 Lint 0 问题。日志：build/app-name-verification.log。

使用 aapt dump badging 核实双 Debug APK 的 application-label 均为 DStationery，offline APK 无 INTERNET/ACCESS_NETWORK_STATE 权限；依赖和网络实现未变，依赖边界沿用上节结果。未运行旧全套测试、真机桌面名称验收或安装，未提交、推送、发布。

产物位于 app/build/outputs/apk/{offline,production}/debug/：
- offline（环境 none）app-offline-debug.apk，SHA-256：6837e8287e0b21353c8b3a909da9fb6eeefd0aa362a2eab02831fcd14888b398。
- production（https://api.rochelimit.me）app-production-debug.apk，SHA-256：64cf3a0fa885fdf6762cc0df1a9869cb933b7363b5b3451d8d06ab0240476264。

- 2026-10-31：[体温询问与统计验证](temperature-verification-2026-10-31.md)，含分批活动测试、production 截图、边界检查和本地 Debug APK 校验值。
