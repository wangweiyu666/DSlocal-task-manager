# 联网版开发、同步与运维指南

本文合并联网版路线图、阶段验收、同步协议、状态矩阵和环境恢复规则。完整任务业务语义以[需求总稿](preview.md)为准，机器契约以 `cloud/openapi.yaml`、`cloud/schemas/` 与 `cloud-protocol-test-vectors/` 为准。

> 当前状态：阶段 1 和阶段 2 已完成；staging 基础设施、同步服务、管理员 Web、执行者 Android、账号邀请箱以及真实双账号/真机闭环已经验证。production、导出/删除、安全专项和正式分发仍属后续阶段。

## 产品与架构边界

| 组件 | 职责 | 存储与隔离 |
| --- | --- | --- |
| 联网 Android | 邮箱登录、邀请领取、空间任务、离线执行、同步和应用内通知 | 独立包名、签名、SQLCipher Room 与 Keystore 会话 |
| 管理员 Web | 成员、任务库、积分组、发布、结果、冲突和审计 | 独立 PWA identity、IndexedDB 缓存与 outbox |
| API Worker + D1 | 鉴权、授权、版本、同步、结果、通知和审计 | local/staging/production 分 Worker、分库、分密钥 |

联网和离线构建共享协议、领域规则、任务编辑器和基础 UI。Android 共享 `DstNavigation`、`ProfileScreen`、`SettingsScreen` 和执行状态机；联网状态通过参数和回调显示，离线构建隐藏对应入口。账号、成员、同步和云数据库实现保留在 connected 源集。编译期隔离规则见[离线版指南](offline.md#共享实现规则)。

## 用户主流程

```text
管理员邮箱登录 Web
→ 创建空间并邀请一个或多个执行者
→ 发布给全部或指定执行者
→ 执行者登录 Android 后在账号邀请箱领取邀请
→ Android 同步并缓存任务
→ 执行者可断网完成任务
→ 联网后 outbox 自动重试
→ Web 查看告知正文、结果、冲突与审计记录
```

邀请绑定目标邮箱账号，72 小时有效且一次有效。邮件只通知，不携带领取链接或令牌；只要邀请仍有效且未领取，每次登录都再次显示。移除后重新邀请复用原成员记录，只恢复 `ALL` 模式活动任务，不恢复旧的 `SELECTED` 分配。

## 身份与会话

- 邮箱验证码为六位数字，10 分钟过期、最多尝试 5 次；新挑战使旧挑战失效。
- 同邮箱发送间隔 60 秒，每小时 5 次、每天 20 次；同 IP 每小时 20 次。响应不能泄露账号或白名单状态。
- access token 有效期 15 分钟；refresh token 每次使用后轮换，设备会话闲置 30 天、最长 90 天。
- 服务端只保存带密钥摘要。超出并发宽限后的 refresh token 重放会撤销设备会话。
- Web refresh token 使用 `HttpOnly; Secure; SameSite=Strict` cookie；Android 使用 Keystore 保护。
- 临时网络或服务器故障保留账号、缓存和 outbox，允许继续离线使用；只有确认成员资格被撤销后才清除该空间业务数据。

## 同步协议

公开 HTTPS JSON API 使用 `/v1`。UTF-8 文本在边界规范化为 NFC，时间使用 UTC RFC 3339，云实体和命令使用 UUIDv7。请求拒绝未知字段；破坏性协议变更提升主版本。

### 下载

`GET /v1/spaces/{spaceId}/changes?cursor=...&limit=...`

- 空间 change sequence 单调递增，cursor 绑定空间和协议版本但不替代授权。
- 默认每页 100 条、最大 500 条；change 保留 90 天。
- 业务数据和 change row 在同一事务提交；热路径使用 `(space_id, sequence)` 索引。
- 首次同步或 `SYNC_CURSOR_EXPIRED` 时分页读取固定高水位快照，再衔接增量 cursor。
- 不支持的 `payloadVersion` 必须停在当前 cursor 并提示升级，不能跳过。

### 上传

`POST /v1/spaces/{spaceId}/commands`

- 每批最多 100 条，按顺序处理，每条命令独立事务。
- 命令包含 `commandId`、`entityId`、`baseVersion`、`createdAt`、`type` 和严格校验的 `payload`。
- 单条结果为 `accepted`、`duplicate`、`conflict`、`rejected` 或 `retryable`。
- 相同 ID/相同载荷重放返回首次回执；相同 ID/不同载荷返回 `IDEMPOTENCY_KEY_REUSED`。
- 客户端仅在收到确定结果后删除 outbox；会话过期和可重试错误不能丢弃 outbox。

### 时区与实例

空间使用版本化 IANA 时区。occurrence key 固定为：

```text
taskId:taskRevision:timeZoneVersion:scheduledLocalTime
```

已生成实例永久保留其任务修订、时区版本和计划时刻。主题、动效、设备提醒等个人偏好只在当前设备保存，不上传或跨设备同步。

## 任务、分配与结果

- 发布和编辑创建不可变任务修订；编辑或取消必须携带当前基线版本。
- `assignmentMode=ALL` 覆盖所有当前及未来执行者；`SELECTED` 只覆盖指定成员。
- 每名执行者拥有独立 assignment。取消勾选会取消该成员分配，并在 Android 同步后移除对应云任务。
- 执行事件不可变，绑定执行者实际看到的任务版本；旧修订或取消后的结果保留并标记待复核。
- 信息告知正文使用独立不可变命令同步，并展示在管理员结果卡片。
- 同一分配的首个有效终态结果暂定生效，后到结果作为 duplicate 保留；管理员改选写入新审计事件。

关键竞态结果：

| 场景 | 结果 |
| --- | --- |
| 管理员用过期基线编辑 | `TASK_VERSION_CONFLICT`，不自动合并 |
| 执行者提交已取消或旧修订任务 | 保存事实并标记 `needsReview` |
| 两台设备提交同一终态 | 先接受者暂定正式，后者保留为 duplicate |
| 批次中单条冲突 | 已成功命令不回滚，继续处理无依赖命令 |
| access token 过期 | 保留缓存/outbox并尝试 refresh |
| 设备会话撤销 | 保留本地数据，邮箱重验后恢复 |
| 联机确认成员被移除 | 清除空间缓存和 outbox，永久拒绝旧命令 |
| 新执行者加入 | 获得活动 `ALL` 任务，不获得既有 `SELECTED` 任务 |

## Android 交互

- “今日”不显示独立联网横条；同步复用离线导入按钮的共享悬浮位置和布局。
- 进入可用空间后每 15 秒刷新通知；新未读通知显示应用内 Snackbar，不使用远程推送。
- “我的”左上角显示与设置按钮同尺寸的通知图标和未读角标，通知中心、同步状态与退出账号复用共享页面。
- 云端单任务载荷错误时隔离该任务并提示“云任务待修复”，其他本地任务和离线操作继续可用；后续收到正确修订后自动恢复。
- 首次快照、增量同步、网络恢复和手动同步均复用本地导入和执行逻辑。

## 稳定错误与安全

非 2xx 响应统一返回 `error.code`、安全的用户消息、`requestId`、`retryable` 和可选重试时间。稳定码至少包括：`INVALID_REQUEST`、`UNAUTHENTICATED`、`SESSION_EXPIRED`、`MEMBERSHIP_REVOKED`、`FORBIDDEN`、`NOT_FOUND`、`RATE_LIMITED`、`INVITATION_INVALID`、`IDEMPOTENCY_KEY_REUSED`、`TASK_VERSION_CONFLICT`、`SYNC_CURSOR_EXPIRED`、`CONFLICT`、`INTERNAL_ERROR`。

- 每次空间查询必须同时约束 `space_id` 和对象 ID。
- Web façade 校验精确 Origin 与 CSRF token；响应启用 CSP、HSTS、nosniff、严格 referrer policy 和 frame deny。
- 日志不得包含任务正文、完整邮箱、验证码、访问/刷新令牌、密钥或请求体。
- 不提供端到端加密；运营服务技术上可读取任务内容，因此必须最小化日志和权限。
- 不接入产品分析、行为遥测或崩溃正文上传。

## 环境与部署

| 环境 | API | Web | D1 |
| --- | --- | --- | --- |
| local | Wrangler 本地进程 | Wrangler 本地进程 | `dstationery-local` |
| staging | `api-staging.rochelimit.me` | `staging.rochelimit.me` | `dstationery-staging`（APAC） |
| production | `api.rochelimit.me` | `app.rochelimit.me` | `dstationery-production`（APAC） |

local、staging、production 使用不同 D1、`AUTH_PEPPER`、管理员白名单、Resend key 和 Cloudflare token。`cloud/scripts/guard-environment.mjs` 在远程部署前验证环境和数据库隔离。默认命令只能操作 local；远程 migration 必须同时指定数据库名、`--remote` 和 `--env`。

联网 Android 只在本机构建，不由 GitHub Android CI 生成或上传：

```powershell
.\gradlew.bat testConnectedDebugUnitTest lintConnectedDebug assembleConnectedDebug --no-daemon
```

当前联网开发版为 `0.1.0-alpha.9`（versionCode 10），staging API 固定为 `https://api-staging.rochelimit.me`。联网 APK 使用独立签名配置 `local-task-manager-connected-signing.properties`，不得复用离线密钥。

Cloud GitHub 工作流仍负责 Worker 与 Web 的测试；`main` 自动部署 staging，production 仅允许受保护的手动工作流。

## Migration、恢复与故障处理

- migration 只追加，已经远程应用的文件不能修改；PR 必须从空本地 D1 执行全部 migrations。
- staging 自动 migration；production 必须人工批准，并在变更前记录固定 Worker 版本和 D1 Time Travel bookmark。
- 恢复演练只使用非敏感 canary，不导出验证码、令牌或任务正文。
- Worker 故障优先回滚固定代码版本；兼容 migration 故障先回滚 Worker，再按 bookmark 使用 D1 Time Travel。
- 邮件故障保留限流事实并返回可重试错误；密钥疑似泄露时立即轮换对应环境密钥并撤销设备会话。
- 达到资源预警阈值时延长自动刷新间隔，接近硬限制时停用定时刷新但保留手动同步和关键写入；上线前重新核对 Cloudflare 官方限额。

## 已完成证据与后续阶段

阶段 1 已完成四构建入口、D1 schema/migration、认证邀请、环境隔离、自定义 staging 域、Resend 投递和 Time Travel 演练。阶段 2 已完成 schema v5、同步 API、管理员 Web、联网 Android、账号邀请箱、指定执行者、断网执行/恢复同步、成员撤销以及 API 26/33/35 验证。

阶段 3 待完成：按角色导出、30 天删除/永久删除、隐私首次提示、授权与滥用安全专项、日志复核和额度观测。

阶段 4 待完成：production DNS/邮件域、受保护 migration、独立签名分发、真实双人试运行和一周观察。远程推送、完整 DSTB1 云迁移、端到端加密、多管理员、多空间、公开注册、应用商店分发和大陆 SLA 均后置。

## 变更清单

- 协议/API：更新 OpenAPI、Schema、TypeScript/Kotlin 共用向量和稳定错误码。
- 云 schema：追加 migration，验证授权索引、快照、增量与 Time Travel 恢复。
- 共享任务/UI：同时回归离线和联网 Android/Web。
- 联网 Android：本地完成测试、Lint、构建、签名和 SHA-256；GitHub 不上传联网 APK。
- 提交前运行 secrets 检查，禁止提交 `.dev.vars`、密钥、令牌、验证码、完整私人邮箱或任务正文。
