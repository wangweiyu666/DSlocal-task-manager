# 离线版开发与交付指南

本文是离线 Android 和离线 Web 的实现、数据边界、构建与发布入口。完整业务语义以[需求总稿](preview.md)为准，DST1/DST1.1 与 DSTB1 的线格式分别以对应 Schema、测试向量和格式文档为准。

## 产品边界

| 产品 | 职责 | 数据与网络边界 |
| --- | --- | --- |
| 离线 Android | 导入任务、离线执行、历史、结果、积分、提醒和备份恢复 | 包名 `com.ds.localtaskmanager`；Room 本地存储；不得声明网络权限 |
| 离线 Web | 任务库、积分组、任务编辑、DST1 生成和本地备份 | IndexedDB 本地存储；不得调用云 API |

离线版不登录、不上传、不遥测、不检查在线更新。Android 只申请完成本地提醒所需的通知、重启、振动和唤醒权限，不申请互联网、照片或广泛存储权限。系统文件选择器负责 DST1/DSTB1 的导入导出。

## 共享实现规则

- Android 的领域模型、执行状态机、Room DAO、DST1 导入、今日/历史/我的/设置页面与 Compose 组件优先放在 `app/src/main`。
- Web 的任务模型、协议、任务编辑器、任务库筛选和积分组组件优先放在 `web/src` 的共享区域。
- 仅联网版使用的账号、成员、云同步和冲突逻辑放入 connected 源集或 connected 入口。
- 仅入口或动作不同、但布局和业务结构相同的组件，使用可空状态或能力回调隐藏联网功能；不要复制整页。
- 安全边界不能仅靠隐藏：离线 Android 必须在编译期排除网络权限、云 API 和 SQLCipher 依赖，离线 Web bundle 必须排除认证与云 API 代码。
- 修改共享业务时同时验证离线和联网变体；修改联网专属能力时至少回归离线边界检查。

## Android 当前能力

- 支持 DST1/DST1.1 严格导入、任务预览、重复任务单日例外和确定性 ID。
- 支持计数、计时、信息告知、步骤任务、延期、重新开启和撤销完成。
- 支持每日/每周重复实例、凌晨 4 点任务日、错过状态和结果重算。
- 今日页按积分组和状态展示；历史页支持分页、筛选、日历与只读详情。
- “我的”提供个人统计、积分组和积分流水；设置保存主题、动效、提醒和隐私偏好。
- 本地通知只展示隐私安全摘要，重启、时区或时间变化后重新计划。
- DSTB1 支持完整备份、合并恢复、完全替换和事务回滚。

导航由共享 `DstNavigation` 管理。首页返回键退出，二级页返回上一层，弹窗和表单优先关闭自身。离线版今日页右下角显示共享导入悬浮按钮；联网版在同一位置显示同步动作。

## 本地数据与兼容

Room 数据库当前 schema version 为 7，历史迁移必须保持连续且禁止破坏性回退。6→7 新增按任务 ID 和实例键关联的 `mood_submission`，实例删除时级联删除心情记录。核心表族包括：

- 导入批次、任务定义、步骤、重复例外和任务实例；
- 执行进度、信息告知、心情记录、备注、操作日志和结果修订；
- 积分组、积分流水、提醒记录、设置和统计索引。

任务实例保存定义快照，已发生的执行结果不能因后来编辑任务而被静默改写。结构变更必须同时提交 migration、导出 Room Schema、数据保留测试，并更新本文或机器契约。

DST1 是任务传输协议，不携带账号和同步语义。DSTB1 是本地备份容器，可能含任务正文、告知内容、备注和积分记录；它不加密，导出前必须明确提示持有文件者可以读取内容。

## Web 当前能力

离线 Web 提供响应式任务库、积分组、完整任务编辑器、DST1 生成和本地备份。它与联网管理员 Web 复用任务模型和编辑组件，但通过独立入口、PWA identity、缓存和构建目录隔离。`npm run verify:boundaries` 必须确认离线 bundle 不含认证端点或云 API 标记。

## 构建职责

离线 Android 由 GitHub Actions 构建，不把本机产物作为交付依据。推送到 `main` 后，`Android CI` 执行：

```text
testConnectedDebugUnitTest（共用及联网业务测试统一执行一次）
lintOfflineRelease
assembleOfflineDebug
assembleOfflineDebugAndroidTest
validateOfflineDebugScreenshotTest
```

Ubuntu job 还检查离线 Manifest 和运行时依赖边界，并将 `app-offline-debug.apk` 与 SHA-256 文件上传为 `DStationery-offline-debug` artifact。Windows job验证固定环境下的截图基线。日常 CI 不在三个变体重复执行同一套单元测试；需要检查离线专属实现或构建差异时，可定向执行 offline 测试，或在手动 CI 中勾选 `full_android_matrix`。

离线 Web 由 `Dom Web Pages` 执行测试、边界检查和 `build:offline`，部署目录固定为 `web/dist-offline`。

本机需要定向复现时使用 JDK 17、Android SDK 35：

```powershell
.\gradlew.bat testOfflineDebugUnitTest lintOfflineRelease assembleOfflineDebug --no-daemon
cd web
npm ci
npm test
npm run build:offline
npm run verify:boundaries
```

模拟器配置与三档 API 回归命令见[Android 模拟器测试环境](android-emulator-testing.md)。

## 发布与签名

离线开发版当前为 `0.1.0-alpha.6`（versionCode 7），公开 Release 仍以仓库 Release 页面为准。

Release 密钥只保存在仓库外的 Gradle 用户目录。`local-task-manager-signing.properties` 必须提供 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；密钥与密码分开做加密备份。发布前必须完成：

1. 单元测试、Release Lint、API 26/33/35 仪器测试与截图验证；
2. Manifest/依赖联网边界检查和 `git diff --check`；
3. APK 签名、版本、minSdk/targetSdk 与 SHA-256 校验；
4. 隐私、许可、CHANGELOG 和下载链接复核；
5. 从旧 Debug 签名迁移时，先导出 DSTB1，再卸载 Debug、安装 Release 并恢复。

## 历史实施摘要

W00–W33 已完成协议与数据库基线、执行与重复任务、结果和积分、延期与重开、本地通知、今日/历史/分享/统计/设置、DSTB1、发布加固和内置配色。历史窗口的逐项记录保留在 Git 历史中，不再作为并行权威文档维护。

## 变更清单

- 业务语义：更新 `preview.md` 和对应测试。
- DST1：更新 Schema、测试向量、Android/Web 解析器测试。
- Room：更新 migration、导出 Schema和迁移测试。
- DSTB1：更新格式、向量、备份/恢复与回滚测试。
- 共享 UI 或领域逻辑：同时构建并测试 offline/connected。
- 发布：由 GitHub Actions 生成离线 artifact，本机不得用联网构建替代离线 CI 证据。
