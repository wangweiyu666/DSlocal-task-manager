# Android 后台同步真机对照（2026-09-09）

## 结论

本次小米 Android 15 真机的阻塞原因是系统拒绝后台自启动。完成回调、待上传记录和 WorkManager 登记正常；关闭自启动时，结束应用后台进程后恢复网络，系统拒绝启动 `SystemJobService`。临时开启自启动后，同样的操作由系统启动新进程，并在约 12 秒内收到上传确认。

验证仅使用已授权的测试执行者和专用零积分任务。没有触碰其他执行者结果，没有清除应用数据，没有使用 force-stop 或手动同步代替后台验收。

## 证据时间线（Asia/Hong_Kong）

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

## 环境与恢复

安装包为前轮构建的 production 诊断 Release APK，基于 `c60cf3ff1e67694b1b34d280575536f842e43269` 加本地诊断改动，`syncDiagnostics=true`，版本 `0.1.0-alpha.13-executor` / 14。签名沿用正式执行者包；这不是 GitHub Release 中的普通 APK。原始设备日志保存在本机忽略目录，不加入发布附件。

本轮未重新构建 APK。测试后已验证：自启动恢复关闭，Wi-Fi 恢复开启，移动数据维持关闭。诊断版和测试数据保留。系统设置恢复后，不应声称该手机仍能在同样的进程结束场景下独立后台同步。

此结论仅覆盖这台小米 Android 15 的对照场景，不代替 API 26/33、其他厂商、重启以及全部发布设备门禁。现有 GitHub Releases 保持草稿；不因单项后台场景通过而自动公开发布。
# 前后台轮询修复补充（本地源码）

通知轮询改由 `ConnectivityContent` 的 `repeatOnLifecycle(STARTED)` 管理：页面不可见时取消循环、网络回调及页面数据观察，重新可见时恢复单个循环。已发出的网络请求可能完成收尾；取消后不再由该轮询触发新的同步。Application 级 WorkManager 的持久化补传与重试保持独立。

本次在 `c60cf3f` 基础的未提交工作区验证，命令：

```powershell
.\gradlew.bat testProductionDebugUnitTest --tests com.ds.localtaskmanager.connected.ConnectedSyncEngineTest --tests com.ds.localtaskmanager.connected.ConnectedRuntimeTest --tests com.ds.localtaskmanager.connected.ConnectedSyncCoordinatorTest --offline --no-daemon
```

使用本机 Gradle/Android 缓存及离线 Robolectric 依赖。最终 23 条通过，失败 0、错误 0、跳过 0。新增测试覆盖三轮前后台切换、15 秒边界、后台不轮询、恢复不重复和后台执行入口可用。过程中修正了显式返回类型编译错误、新测试的缺失游标与协程测试线程等待方式；未跳过或弱化断言。通过后仅调整缩进与文档。

本修复尚未构建、安装或完成新的真机验证；下文的真机结果属于修复前诊断 APK，不能视为新版设备验收。
