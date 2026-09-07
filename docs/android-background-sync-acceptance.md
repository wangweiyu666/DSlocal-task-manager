# Android 后台同步补偿验收

> 2026-09-07 后续决定取消 staging，之后使用 production 进行获准的专用任务验证。下方 staging 安装、APK 和测试数量保留为当时证据，不能视为正式环境真机同步已通过。当前环境方案见[单一云端环境](production-only.md)。

本清单约束一次性 WorkManager 同步补偿的行为。自动化测试结果和设备验证结果分别记录，不能以 JVM 测试代替真实系统的后台调度验收。

## 实现约束

联网版使用普通一次性 `OneTimeWorkRequest`，网络约束为 `CONNECTED`，默认采用 30 秒指数退避。单次 Worker 最长运行时间为 3 分钟；这是执行上限，和服务端 `Retry-After` 截止时间分别生效，后者会持久化并阻止前台绕过等待。前台和 Worker 共用 Application 级 Engine 与互斥锁，启动恢复从本地数据库扫描未登记的完成、撤销和结果事件；事务成功提交后再异步触发登记。离线版启动桥接和提交回调均为空操作。

会话 generation 与 account、membership、space 一起校验，旧 Worker 不能操作新会话。ConnectedSyncDatabase v1→2 保留会话、命令、已发送标记、游标和 occurrence map；主 AppDatabase 保持版本 8。失败、取消和响应丢失都保留未确认命令及其原始 JSON；没有待办且没有显式同步请求时不会额外拉取。强行停止后的补偿依赖用户重新打开应用触发启动恢复。

## 恢复与并发

| 场景 | 必须观察的结果 |
| --- | --- |
| 本机完成事务提交后、登记 WorkManager 前进程中断 | 重启后从本机结果及撤销日志补建队列；不依赖详情页回调 |
| 无网络完成、恢复网络 | 本机结果立即保留；具备网络条件后重试使用原命令标识和答案快照 |
| 最后一条上传尚未返回时新增业务变更 | 新变更获得后继执行机会；队列已清空的后继任务不发起请求 |
| 前台同步与 Worker 同时执行且令牌需要刷新 | 共用互斥协调器，不能并行刷新或上传同一队列 |
| 服务端已接受但客户端未收到响应 | 重试保持命令不变；收到 duplicate 后原子地记为已发送并移除队列 |
| Worker 被取消或达到单次时限 | 取消正常传播，未确认命令仍在本机；恢复时不能凭空标记成功 |
| 完成、撤销、再次完成快速连续发生 | 保留事件顺序和身份；撤销事件不带答案，草稿不进入上传结果 |

## 身份与失败处理

| 场景 | 必须观察的结果 |
| --- | --- |
| 退出后换号，或同账号重新登录 | 旧会话的 Worker 不能使用新凭据，也不能清除新会话数据 |
| 成员资格失效、账号删除或需确认隐私条款 | 按原业务规则隔离或清理；需要用户操作时停止自动重试 |
| 429／503 带 Retry-After 后进程重启 | 等待截止时间仍有效，前台同步也不能绕过 |
| 永久拒绝或冲突 | 保存诊断和原命令，不静默丢弃，也不无限重试 |
| 完成草稿编辑但未完成任务 | 不上传心情、分步骤或私人备注草稿 |
| ConnectedSyncDatabase 1 升级至 2 | 原会话、待发命令、已发送标记、游标和实例映射保留；主数据库版本不变 |

## 构建与设备验证

先运行受影响用例，再运行一次 connected 的共用及联网完整单元套件。三个变体保留构建、Lint 和离线网络边界检查；已有通过结果无需为打包重复运行。实际命令、通过／失败／跳过数量、源码状态和 APK 校验值应随交付记录提供。

真实设备需要补充：断网提交后退到后台再恢复网络、系统回收进程后的补偿、系统取消正在执行的工作，以及强行停止后重新打开应用的恢复。一次性后台工作受系统调度约束，不保证立即执行；强行停止后需重新打开应用。缺少设备时须明确标为未验证。

## 实际结果

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
