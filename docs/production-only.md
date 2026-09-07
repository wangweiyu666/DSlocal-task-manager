# 单一云端环境

2026-09-07 用户决定取消 staging，云端统一使用 production；离线应用继续保留。本文取代旧文档中要求 staging 部署、三变体构建和 staging 恢复演练的流程。

## 入口和兼容

- 正式管理网页目标：`https://prod.rochelimit.me`。原正式入口 `staging.rochelimit.me` 在新域名与 Access 验收成功后退役；配置变更不代表远程切换已经完成。
- 正式 API：`https://api.rochelimit.me`。
- Android 只构建 `offline` 和 `production`。保留现有 production 包名、签名、版本和本机存储身份；联网共享源码仍位于 `src/connected`，测试仍位于 `src/testConnected`。
- 旧 staging 包停止构建，不把其 API 地址改为 production；旧测试空间、会话和待上传数据不能直接提交到正式环境。
- 本地开发、模拟 HTTP 和隔离数据库测试继续使用 local。删除、恢复与故障注入测试不能改为操作正式业务数据。

## 验证和发布

默认运行一次 `testProductionDebugUnitTest`，覆盖共享及联网测试；保留 `lintOfflineRelease lintProductionDebug assembleOfflineDebug assembleProductionDebug` 和离线联网边界检查。修改 flavor、签名或构建配置时追加 `testOfflineDebugUnitTest`；手动 CI 的 `full_android_matrix` 现在表示这两个变体。

Cloud push/PR 只验证；手动发布仍要求固定且通过验证的 SHA、`DEPLOY_PRODUCTION` 和 `cloud-production` 环境保护。主代理负责实现、验证、提交、推送、CI 跟进、部署和迁移。所有既有自动调用 Luna 的设定已取消；今后子智能体使用先讨论并取得新的明确授权。生产发布不再依赖已取消的 staging 运行。继续执行原生产手册的迁移前恢复记录、Access 检查和真实管理员页面加载验收。

Wrangler 配置及 npm scripts 已移除 staging 部署入口。旧云资源退役单独记录实际远程结果；本地配置移除不代表云资源已经停用。旧 staging 主库及删除账本保留，不合并或删除；正式数据不变。

## Cloudflare CLI

项目在 `cloud` 和 `cloud-web` 使用 Wrangler。进入对应目录后可运行 `npm exec --no -- wrangler --version`、`npm exec --no -- wrangler whoami`。身份检查可能显示账号资料，自动化汇报应仅记录认证是否成功。发布、数据库操作及密钥配置分别沿用授权范围。

本机已核实 Node.js 24.19.0、Wrangler 4.123.0。CLI 可运行与拥有远程权限是两项独立检查。

## 正式管理域名切换

先为 `prod.rochelimit.me` 准备 production Access 应用、精确管理员策略和 DNS；保留原有保护直到新入口通过验证。API 地址保持 `api.rochelimit.me`，将其 `ALLOWED_ORIGIN` secret 更新为 `https://prod.rochelimit.me`。API/Web 的 Access audience 必须对应新的正式管理应用；敏感配置仍保存在 secrets 中。

发布同一验证版本的 API/Web，核对网页路由、`MANAGEMENT_HOST`、API service binding 以及 Access 同源身份交换。新入口匿名 HTML、静态资源和代理 API 必须经过 Access；管理员登录后可进入任务库并刷新。旧域名停止服务或在受保护条件下引导到新入口，不能变成旁路。迁移前提醒使用者同步旧网页未上传的变更：IndexedDB 和会话属于旧源，不会随域名自动迁移。

## 2026-09-07 执行记录

- 本地完成：移除 staging flavor、Wrangler 环境和 CI 部署任务，正式管理目标改为 `prod.rochelimit.me`。域名发布与真实登录验收结果单独记录。
- Luna 验证：Cloud 36/36、Cloud Web 7/7 测试通过；production guard 通过，staging guard 按预期拒绝。工作流静态审查通过，未进行专用 YAML 解析。
- 远程只读：旧测试网页与旧正式网页仍返回 Access 跳转，两个 API 返回未认证响应。新 `prod` 域名有 DNS 记录，但 HTTPS 握手失败，不能判定已接入。
- 授权进展：用户完成 Wrangler OAuth 登录，并提供仅限当前账户 Access 应用/策略及 `rochelimit.me` DNS 编辑的本机令牌。令牌不纳入 Git。新 `prod.rochelimit.me` Access 应用及一条管理员策略已创建；主代理读回确认策略内容、身份提供方、会话和 Cookie 安全设置与原正式入口一致。
- staging API cron 已停用，原 `*/5 * * * *` 已记录供回滚；尚不代表旧网页/API 入口已经退役。所有数据库保留。
- 停用旧 staging 时须同时处理域名入口及维护 cron；仅解除域名不足以停止后台维护。保留两个 D1 数据库和可回退的 Worker 配置。
- 最新远程事实：本机 Wrangler OAuth 已重新授权；另以仓库外窄权限 token 完成 Access/DNS 操作。旧 `dstationery-api-staging` 的 `*/5 * * * *` cron 已清空并读回确认 0，原值保存在本机私有回滚记录中；staging Worker、两个 staging D1 和旧域名入口仍保留。
- 已创建 `prod.rochelimit.me` 的独立 Access self-hosted 应用，并复制旧正式入口的唯一管理员策略；已读回确认 `allowed_idps`、policy 的 `decision/include/exclude/require` 及 24 小时、自动跳转、Binding Cookie、HttpOnly 设置一致。新应用尚未完成 custom domain、DNS、production Worker 或 API origin 切换，尚未完成新域真实管理员登录验收。
- 旧 `staging.rochelimit.me`、`test.rochelimit.me`、`api-staging.rochelimit.me` 仍作为回滚入口保留；`api.rochelimit.me` 与正式数据未修改。本文中的 APK 测试数据来自含未提交 WorkManager 改动的工作树，不代表本次环境收敛提交的 CI 结果。

### Android flavor 验证

以下为包含尚未提交的 Android 后台同步实现的工作树验证与 APK，不是仅包含域名迁移提交的产物；发布提交的 CI 与对应 APK 应另行记录，不能混用。

- 环境：JDK 17.0.19、ADB 35.0.2。
- `testProductionDebugUnitTest`：使用缓存 Robolectric 运行包及 `--offline`，173 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESSFUL`。
- `testOfflineDebugUnitTest`：使用同一离线缓存配置，145 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESSFUL`。
- `lintOfflineRelease lintProductionDebug assembleOfflineDebug assembleProductionDebug --no-daemon`：`BUILD SUCCESSFUL`，两变体 APK 与 Lint 均完成。
- 离线边界：`.\gradlew.bat :app:dependencies --configuration offlineReleaseRuntimeClasspath --no-daemon` 输出无 `okhttp`、`retrofit`、`ktor-client`；offlineRelease 合并 Manifest 无 `INTERNET` 或 `ACCESS_NETWORK_STATE` 权限，仅保留通知、启动、振动、唤醒锁、前台服务及动态接收器权限。
- APK：`app/build/outputs/apk/offline/debug/app-offline-debug.apk` SHA-256 `37A11590B8C38351FEEDA1B75C6369FC207913217FB16C2ED3E6C7F5140FA396`；`app/build/outputs/apk/production/debug/app-production-debug.apk` SHA-256 `546B318E358743DB4C0C6CFD5C0BCCC2B2060D23BF23A5AE174866F402C96078`。
- 测试使用本机缓存 Robolectric 依赖；未进行设备安装或生产环境操作。Android 单元测试结果不替代真实设备生命周期验证。
