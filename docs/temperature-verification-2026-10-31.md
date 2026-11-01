# 体温询问与统计验证记录（2026-10-31）

源码为基线 `8583c7e8d4dbbe0d1144ce8be46bcf523864ec4c` 上的未提交工作区，包含原有提醒、同步修改及本次体温功能；不是已发布的固定提交。没有执行提交、推送、部署、GitHub 发布或设备安装。

## 行为与接口

- 新增叶执行 `{k:8}` / `TEMPERATURE`，答案字段 `temperatureTenthsC` 为 350～420 的整数。Android 使用 35.0～42.0℃、0.1℃步长滑动条，初始未选择，支持独立任务和步骤、草稿、完成锁定、撤销、历史与备份。
- Room 10→11；DSTB1 payload 7（读取 1～7）；DSDOM 1.4（保留旧版本读取）。体温完成答案按实例隔离，草稿不上传，跳过或不适用步骤不携带答案。
- 三个编辑入口共用执行方式分组。联网 DOM 新增体温统计，按执行者、任务及空间自然日筛选，展示每次正式记录、多系列曲线、汇总与明细。
- 统计与结果列表共用正式结果选择规则。snapshot 的实体增加可选 `changeSequence`，增量同步保留原有 `sequence`，避免同毫秒改选发生顺序歧义。

## Web / Cloud

| 检查 | 实际结果 |
| --- | --- |
| Web 最小活动集合 | 24 个场景最终通过；分批执行并复用未受修改影响的已通过结果 |
| Cloud 最小活动集合 | 16 个场景最终通过；初次最小入口另有 11 个旧场景按筛选跳过 |
| Web / Cloud / cloud-web 类型检查 | 通过 |
| 协议生成一致性、offline / connected Web 构建、构建边界 | 通过 |
| 最终 connected 构建 CSP smoke | 1 通过、0 失败、0 跳过；覆盖统计入口、数据点、明细、375px 布局及控制台/CSP 检查 |

实际命令：

```text
node --version                                  # v24.19.0
npm --prefix web run test:minimum
npm --prefix cloud run test:minimum
npm exec -- vitest run tests/temperature.test.tsx tests/custom-reminder.test.tsx   # cwd: web
npm exec -- vitest run tests/temperature.test.tsx                               # cwd: web
npm exec -- vitest run tests/temperature.test.tsx -t "distinguish tasks"          # cwd: web
npm exec -- vitest run tests/temperature.test.ts                               # cwd: cloud
npm --prefix web run typecheck
npm --prefix cloud run typecheck
npm --prefix cloud-web run typecheck
npm --prefix web run build:all
npm --prefix web run test:connected-smoke
```

初次 Web 最小入口为 19 通过 / 3 失败，修正备份版本断言及新测试的重复元素查询后相关文件通过；新加持久化测试曾因 IndexedDB 测试初始化导入顺序失败，修正后体温文件 12 / 12 通过。跨任务多系列追加断言单独复测 1 通过、11 按筛选跳过。Cloud 初次为 13 通过 / 2 失败 / 11 跳过，失败来自不合法的普通任务测试夹具；修正后体温文件 13 / 13 通过。没有将失败运行算作通过。

## Android

JDK Temurin 17.0.19+10，目标 API 35；本地 SDK adb 37.0.0。Robolectric 使用已缓存的 Android 15 i7 运行包。截图固定 Asia/Hong_Kong、zh-CN、UTF-8。

| 检查 | 实际结果 |
| --- | --- |
| production 活动单元集合 | 初次运行 58 项：55 通过、3 失败；后续按失败和受影响场景复测 |
| 体温执行与两项备份复测 | 8 项：7 通过、1 失败；体温执行 6 项及条件步骤备份通过 |
| 剩余提醒备份兼容复测 | 1 通过、0 失败、0 跳过，包含 payload 5 / 6 / 7 兼容检查 |
| 新增独立体温转步骤／步骤备份测试 | 1 通过、0 失败、0 跳过 |
| `assembleOfflineDebug` / `assembleProductionDebug` | 通过 |
| `lintOfflineRelease` / `lintProductionDebug` | 通过，两份 XML 均为零 issue |
| production 截图矩阵 | 34 通过、0 失败、0 跳过（最终 JUnit XML） |
| offline 网络边界 | 通过：offlineRelease Manifest 无 INTERNET / ACCESS_NETWORK_STATE，运行依赖无 okhttp / retrofit / ktor-client |

最终覆盖 **59 个不同的活动单元场景，均已通过，无未解决失败**。这是首轮 58 项、受影响的 8 项定向复测、剩余 1 项复测和追加 1 项新场景的结果并集，不是重复执行整套；总共 68 次单元场景执行。最后追加场景的 XML 为 `app/build/test-results/testProductionDebugUnitTest/TEST-com.ds.localtaskmanager.data.TemperatureTaskTest.xml`。

单元失败原因：两个旧断言仍期望 payload 6；一个新用例发现固定或回退时钟下，撤销后编辑体温草稿的更新时间可能倒退，导致合并恢复选择旧值。已让体温写入时间随实例和历史答案单调递增，并更新格式版本断言、保留旧格式读取检查。没有删减断言或扩大旧回归集。

实际 Gradle 检查入口：

```text
.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release "-Pkotlin.incremental=false" --no-daemon
.\gradlew.bat testProductionDebugUnitTest --tests "*TemperatureTaskTest" --tests "*ConditionalChoiceTest.*backup*" --tests "*CustomReminderTest.backupRoundtrip*" "-Pkotlin.incremental=false" --offline --no-daemon
.\gradlew.bat testProductionDebugUnitTest --tests "*CustomReminderTest.backupRoundtrip*" lintOfflineRelease assembleOfflineDebug lintProductionDebug assembleProductionDebug testProductionDebugScreenshotTest "-Pkotlin.incremental=false" --no-daemon
.\gradlew.bat testProductionDebugUnitTest --tests "*TemperatureTaskTest.*root*" -I scripts/testing/android-screenshot-matrix.init.gradle validateProductionDebugScreenshotTest "-Pkotlin.incremental=false" --no-daemon
python scripts/testing/inspect-android-profiles.py
git -c core.safecrlf=false diff --check
```

准备过程中遇到沙箱缓存权限、PowerShell 参数拆分及离线仓库元数据缺失，改用当前用户缓存、带引号的属性参数并联网解析缺失依赖。截图插件实际比较入口为 `validateProductionDebugScreenshotTest`；单独的 `testProductionDebugScreenshotTest` 构建成功不作为图片验收通过证据。静态清单现为 59 个方法，不以此替代运行结果。

截图验证首次实际运行时为 31 通过 / 3 失败，失败仅因三张新体温参考图不存在。逐张审核未选择、已选择、大字体/保存失败画面后，只添加这三张共享参考图到 `app/src/screenshotTestOfflineDebug/reference/`，没有覆盖旧基线；最终 production 矩阵 34 / 34 通过，没有运行 offline 截图。

Windows 曾触发 CreateProcess 206，最终按仓库指南临时使用 W: 项目和 G: Gradle 缓存映射，验证后已移除。最终命令在短路径下使用上述属性、截图矩阵 init 脚本，并追加 `:app:dependencies --configuration offlineReleaseRuntimeClasspath`。完整输出保存在 `app/build/temperature-final-validation.log`，边界结论在 `app/build/temperature-boundary-result.txt`；截图 XML 在 `app/build/test-results/validateProductionDebugScreenshotTest/TEST-preview-screenshot-test-engine.xml`。

## 本地 APK

两份均为本工作区构建的 Debug APK，复用构建结果，未安装或发布。复制后的元数据和校验值同时保存在 `release-output/temperature-2026-10-31/apks.json`。

| 变体 | 环境与 applicationId | 实际交付路径 | SHA-256 |
| --- | --- | --- | --- |
| offline | 完全离线；`com.ds.localtaskmanager.debug` | `D:/Projects/local-task-manager/release-output/temperature-2026-10-31/DStationery-offline-debug.apk` | `655DCA2CA55B7C5BC8F7C7697C199BC925EC864FEB5CCEE620B7D677ADB0DABA` |
| production | `https://api.rochelimit.me`；`com.ds.localtaskmanager.connected.production.debug` | `D:/Projects/local-task-manager/release-output/temperature-2026-10-31/DStationery-production-debug.apk` | `AC4832F247A2438AC14362C4EEEFE8C0CA6AD62AAD23A5510956DA615056BB89` |

## 网页截图

最终正式构建的模拟数据截图已归档至 `release-output/temperature-2026-10-31/temperature-web-desktop.png` 与 `temperature-web-mobile.png`，桌面和 375px 手机布局均已查看。

## 覆盖限制

没有运行旧完整回归、offline 单元全集、offline 截图或完整双变体测试矩阵。CSP smoke 使用模拟会话和模拟同步数据；Android production 变体的渲染不代表线上登录、真实 API 回传或真实设备验收。离线 DOM 首版没有新增结果导入或统计。
