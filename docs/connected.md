# 联网版开发、同步与运维指南

本文合并联网版路线图、阶段验收、同步协议、状态矩阵和环境恢复规则。完整任务业务语义以[需求总稿](preview.md)为准，机器契约以 `cloud/openapi.yaml`、`cloud/schemas/` 与 `cloud-protocol-test-vectors/` 为准。

> 当前状态：阶段 1、阶段 2 和阶段 3 已完成。阶段 4 的生产执行者变体、独立签名与发布门禁正在构建；staging/production 删除账本、恢复演练、生产部署、三账号真实试运行和连续七天观察仍必须按[阶段四生产运行手册](stage4-production-runbook.md)留存证据后才能声明完成。

## 产品与架构边界

| 组件 | 职责 | 存储与隔离 |
| --- | --- | --- |
| 联网 Android | 邮箱登录、邀请领取、空间任务、离线执行、同步和应用内通知 | 独立包名、签名、SQLCipher Room 与 Keystore 会话 |
| 管理员 Web | 成员、任务库、积分组、发布、结果、冲突和审计 | 独立构建、Cloudflare Access 管理员登录、IndexedDB 缓存与 outbox |
| API Worker + D1 | 鉴权、授权、版本、同步、结果、通知、审计和生命周期 | local/staging/production 分 Worker、主库、删除账本库与密钥 |

联网和离线构建共享协议、领域规则、任务编辑器和基础 UI。Android 共享 `DstNavigation`、`ProfileScreen`、`SettingsScreen` 和执行状态机；联网状态通过参数和回调显示，离线构建隐藏对应入口。账号、成员、同步和云数据库实现保留在 connected 源集。编译期隔离规则见[离线版指南](offline.md#共享实现规则)。

## 用户主流程

```text
管理员通过 Cloudflare Access 验证后自动进入 Web
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
- 两个管理网页在返回资源或代理 API 前验证 Cloudflare Access 身份，会话设为 24 小时并绑定浏览器。网页通过 `/v1/auth/access` 自动换取现有管理员业务会话，无需第二次邮箱登录；API 独立校验 Access 签名、环境 audience、管理员邮箱及现有 ADMIN 成员，继续执行业务角色授权。
- 临时网络或服务器故障保留账号、缓存和 outbox，允许继续离线使用；只有确认成员资格被撤销后才清除该空间业务数据。

## 隐私、导出与删除

- 服务端按账号保存隐私说明版本和确认时间。新账号或说明版本提升后，客户端必须先显示隐私说明；确认前 `/bootstrap` 和所有空间业务接口返回 `PRIVACY_ACK_REQUIRED`。
- `DSEXPORT v1` 是 UTF-8 JSON，只承诺导出、不承诺导回。管理员可导出整个空间；执行者只能导出自己的账号/成员关系、实际收到的任务修订、分配、实例、执行记录和通知。密钥、令牌、验证码及安全内部记录始终排除。
- 管理员导出只包含当前活动成员和有效邀请的邮箱；已移除、已删除或已过期对象匿名化。每次导出和删除都要求最近 10 分钟内完成邮箱验证，并写入不含正文的审计事件。
- 默认删除会立即把账号和管理员空间置为 `DELETION_PENDING`、撤销所有会话并冻结业务访问，30 天后永久执行。恢复必须重新验证邮箱；立即删除没有恢复窗口。
- 执行者永久删除会匿名化其成员关系与执行事件，仅保留随空间存在的随机 tombstone 和结构关系。唯一管理员永久删除会删除整个空间及其业务数据。
- 客户端确认删除后立即清除会话、缓存和 outbox；其他离线设备在下一次联网确认状态后清除。服务商灾备历史到期前可能仍含旧块，因此每个环境使用独立 D1 删除账本，恢复主库后必须先重放账本。

生命周期保留期：邮箱挑战 24 小时；已结束设备会话 30 天；命令回执和同步 change 90 天；终态邀请邮箱 30 天后脱敏；删除账本 35 天。删除账本只保存随机目标 ID、范围、创建/到期时间，不保存邮箱或业务正文。

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
- schema v7 将业务数据、change、通知、审计与命令回执放入同一 D1 batch；空间序列已变化时整批回滚，客户端可以安全重试。部署此版本前必须应用 `0007_atomic_sync_commands.sql`。
- 命令包含 `commandId`、`entityId`、`baseVersion`、`createdAt`、`type` 和严格校验的 `payload`。
- 单条结果为 `accepted`、`duplicate`、`conflict`、`rejected` 或 `retryable`。
- 相同 ID/相同载荷重放返回首次回执；相同 ID/不同载荷返回 `IDEMPOTENCY_KEY_REUSED`。
- 仅成功回执重放标记为 `duplicate`；`conflict` 和 `rejected` 保持首次状态。管理员 Web 对保留在 outbox 中的命令采用 1、2、4 秒递增至 60 秒的重试间隔，手动同步仍可立即触发。
- 客户端仅在收到确定结果后删除 outbox；会话过期和可重试错误不能丢弃 outbox。

Android 将持久化的撤销日志作为 `EXECUTION_EVENT / COMPLETION_UNDONE` 上传，携带撤销后的状态与发生时间。服务端保留原执行记录、同步新的结果选择，允许随后再次完成；延迟到达的旧撤销不覆盖较新结果，管理员明确选定的结果也不会被自动覆盖。

Web 与云端共用 `shared/protocol` 中的 DST1 类型与语义规则。云端使用预编译的 Schema 校验器，避免 Workers 运行时动态生成代码；修改 Schema 后在 `web` 运行 `npm run generate:protocol`，CI 通过 `npm run verify:protocol` 检查生成文件一致性。

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

API 使用 Workers Rate Limiting binding，在任何 D1/删除账本查询前限流。staging 与 production 各自按来源 IP（`CF-Connecting-IP`）设置所有请求 120 次/60 秒，登录验证码发送、验证、刷新及无 Bearer 请求再共用 20 次/60 秒的较低额度。伪造包名、User-Agent 或 Bearer 不会绕过所有请求的额度，登录接口也不会因带 Bearer 而免限流。缺少凭据的业务请求直接返回 401；CORS 预检不读取数据库。未认证客户端的 IP 可能多人共用，因此这些阈值需要结合实际 429 监测调整。

local 的两项阈值分别为 1200/120，便于串行 smoke 测试；三个环境使用独立的限流 namespace。超限返回 `429/RATE_LIMITED`、`Retry-After: 60` 及 JSON `retryAfterSeconds`。缺少限流绑定时返回 `503/SERVICE_UNAVAILABLE`，部署 guard 检查绑定配置和 namespace 隔离。原有邮箱/IP 验证码限额继续生效。

Android 在同一个 API 客户端实例内，发送验证码至少间隔 60 秒、验证至少 2 秒、刷新至少 1 秒；收到 429（或带重试时间的 503）后暂停该客户端的新网络请求，并显示剩余等待时间。支持 JSON 秒数与 `Retry-After` 的秒数/HTTP 日期，缺少重试时间的 429 默认等待 60 秒，单次最多等待 24 小时；应用重启后的最终保护仍由服务器承担。

这些措施是资源保护，不是官方 APK 身份证明；项目不把 APK 内置共享密钥、包名或自报设备标识当作可信身份。[Workers 限流](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)按 Cloudflare 站点计数、最终一致，不是精确的全球配额或完整 DDoS 防护，仍会执行 Worker。真正限制官方应用需另行接入应用/设备证明，并兼容当前侧载、Debug 和 Web 客户端；本次不启用这种限制。

非 2xx 响应统一返回 `error.code`、安全的用户消息、`requestId`、`retryable` 和可选重试时间。稳定码至少包括：`INVALID_REQUEST`、`REQUEST_TOO_LARGE`、`UNAUTHENTICATED`、`SESSION_EXPIRED`、`PRIVACY_ACK_REQUIRED`、`ACCOUNT_DELETION_PENDING`、`SPACE_DELETION_PENDING`、`REAUTHENTICATION_REQUIRED`、`MEMBERSHIP_REVOKED`、`FORBIDDEN`、`NOT_FOUND`、`RATE_LIMITED`、`EMAIL_CAPACITY_PROTECTED`、`INVITATION_INVALID`、`IDEMPOTENCY_KEY_REUSED`、`TASK_VERSION_CONFLICT`、`SYNC_CURSOR_EXPIRED`、`CONFLICT`、`INTERNAL_ERROR`。

- 每次空间查询必须同时约束 `space_id` 和对象 ID。
- Web façade 校验精确 Origin 与 CSRF token；响应启用 CSP、HSTS、nosniff、严格 referrer policy 和 frame deny。
- 正式 Web 缺少 Access 配置时以 503 失败关闭；无效 JWT 返回 403，边缘匿名请求跳转 Cloudflare 登录。联网 Web 不注册 Service Worker，避免受保护静态资源被离线缓存绕过门禁。
- 日志不得包含任务正文、完整邮箱、验证码、访问/刷新令牌、密钥或请求体。
- 不提供端到端加密；运营服务技术上可读取任务内容，因此必须最小化日志和权限。
- 不接入产品分析、行为遥测或崩溃正文上传。

安全专项采用仓库内可重复测试，不声明第三方渗透测试。测试覆盖隐私门禁、跨空间拒绝、游标签名篡改、幂等键复用、成员移除、敏感操作近期邮箱验证、请求体大小限制、日志字段白名单以及删除流程。

Worker 结构日志只允许 `timestamp`、`level`、`event`、`environment`、`requestId`、`method`、`route`、`status`、`durationMs`、`errorCode`、`retryable` 等字段；不打印原始 URL 查询、header、body、异常 message/stack。运维使用 Cloudflare Workers Logs/D1 仪表盘和 Resend 原生用量页，不接第三方监控。

资源保护按每天的 EMAIL、AUTO_SYNC_READ、API_WRITE 计数：达到软额度 70% 进入 `WARNING`，客户端自动轮询从 15 秒降为 60 秒；达到 90% 进入 `PROTECT`，停止自动轮询并保留手动同步、关键写入和删除/恢复邮件。邮件最后 10% 仅供敏感验证与账号恢复。阈值是应用保护线，不替代供应商硬限额。

## 环境与部署

| 环境 | API | Web | D1 |
| --- | --- | --- | --- |
| local | Wrangler 本地进程 | Wrangler 本地进程 | `dstationery-local` + `dstationery-deletion-ledger-local` |
| staging | `api-staging.rochelimit.me` | `test.rochelimit.me`（Access） | `dstationery-staging` + `dstationery-deletion-ledger-staging`（APAC） |
| production | `api.rochelimit.me` | `staging.rochelimit.me`（Access，名称历史遗留） | `dstationery-production` + `dstationery-deletion-ledger-production`（APAC） |

此表是 Access 迁移后的配置，按[生产运行手册](stage4-production-runbook.md#本次域名与-access-迁移顺序)完成云端配置与验收。local、staging、production 使用不同主 D1、删除账本 D1、`AUTH_PEPPER`、管理员白名单、Resend key 和 Cloudflare token。两个网页使用独立 Access 应用 audience，`ACCESS_TEAM_DOMAIN`、`ACCESS_AUD`、`MANAGEMENT_ADMIN_EMAIL` 保存在 Web Worker secrets 中；API 保存对应的前两项，并使用既有 `ADMIN_EMAIL`。Worker 验证签名、issuer、audience、有效期和精确邮箱；页面、资源和代理 API 均在验证之后返回。Access 自动登录代替网页第二次邮箱验证，业务角色、隐私确认和敏感操作验证继续保留。直接 API 不加浏览器门禁，Android 地址、应用认证和限流保持不变。

`cloud/scripts/guard-environment.mjs` 验证数据库隔离、环境 cron、限流命名空间、Access 开关、整站 Worker 检查、域名和 API 服务绑定，禁止 workers.dev/preview 旁路。默认命令只能操作 local；远程 migration 必须同时指定数据库名、`--remote` 和 `--env`。旧 staging 域改作生产前，应先同步未上传的网页修改并关闭旧标签页；新测试源不能直接读取旧源的本地缓存，测试 D1 数据不会迁往生产。

阶段 3 首次远程部署前必须完成：

1. 分别创建 `dstationery-deletion-ledger-staging` 和 `dstationery-deletion-ledger-production` D1 数据库；
2. 将 `cloud/wrangler.jsonc` 中 `2222…` / `3333…` 占位 ID 替换为真实 ID；
3. 运行环境 guard；
4. 先迁移主库，再迁移删除账本库，最后部署 Worker；
5. 用非敏感 canary 演练“删除 → 主库 Time Travel 恢复 → 账本重放 → canary 仍不可用”。

联网 Android 分为 staging 和 production。CI 验证两者的 Debug 变体，但不上传联网 Debug APK：

```powershell
.\gradlew.bat testConnectedDebugUnitTest lintConnectedDebug assembleConnectedDebug --no-daemon
.\gradlew.bat testProductionDebugUnitTest lintProductionRelease assembleProductionRelease --no-daemon
```

staging 为 `0.1.0-alpha.9-connected`（versionCode 10）、包名 `com.ds.localtaskmanager.connected`，固定连接 staging API。生产执行者为 `0.1.0-alpha.10-executor`（versionCode 11）、包名 `com.ds.localtaskmanager.connected.production`，固定连接 production API。两者因包名不同拥有独立 Android 沙箱、Room、outbox、会话和 Keystore；生产签名配置为 `local-task-manager-connected-production-signing.properties`，不得复用离线或 staging 密钥。

Cloud GitHub 工作流负责 Worker 与 Web 的测试；staging 和 production 都只允许受保护的手动工作流部署，避免普通代码推送在缺少环境密钥时触发远程变更。

## Migration、恢复与故障处理

- migration 只追加，已经远程应用的文件不能修改；PR 必须从空本地 D1 执行全部 migrations。
- staging 和 production migration 均通过受保护的手动工作流执行；production 必须人工批准，并在变更前记录固定 Worker 版本和 D1 Time Travel bookmark。
- 恢复演练只使用非敏感 canary，不导出验证码、令牌或任务正文。
- Worker 故障优先回滚固定代码版本；兼容 migration 故障先回滚 Worker，再按 bookmark 使用 D1 Time Travel。
- 邮件故障保留限流事实并返回可重试错误；密钥疑似泄露时立即轮换对应环境密钥并撤销设备会话。
- 每日查看 Worker 错误率/结构日志、D1 读写与存储、Resend 投递和用量；周度复核日志字段样本与额度趋势。达到 70% 时确认轮询降频，达到 90% 时确认保护模式和邮件保留通道；上线前重新核对 Cloudflare 与 Resend 官方限额。

## 已完成证据与后续阶段

阶段 1 已完成四构建入口、D1 schema/migration、认证邀请、环境隔离、自定义 staging 域、Resend 投递和 Time Travel 演练。阶段 2 已完成 schema v5、同步 API、管理员 Web、联网 Android、账号邀请箱、指定执行者、断网执行/恢复同步、成员撤销以及 API 26/33/35 验证。

阶段 3 已完成 schema v6、按角色 DSEXPORT v1、30 天删除/立即永久删除、版本化隐私首次门禁、近期邮箱验证、授权与滥用自动化、安全日志白名单、原生用量观测和 70%/90% 客户端保护模式。仓库不声称完成第三方渗透测试；staging/production 删除账本仍需在阶段 4 部署前真实创建和演练。

阶段 4 待完成：production 应用层严格白名单部署、受保护 migration、公开 GitHub 执行者 APK、三个账号真实试运行和连续七天观察。两个环境的删除账本、staging 恢复演练和 production 邮件域已完成准备。远程推送、完整 DSTB1 云迁移、端到端加密、多管理员、多空间、公开注册、应用商店分发和大陆 SLA 均后置。

## 变更清单

日常修改先按[最小测试方案](minimal-testing.md)运行受影响的测试；以下双变体、部署和发布要求在对应交付阶段执行，不必每次局部修改都重复完整矩阵。

- 协议/API：更新 OpenAPI、Schema、TypeScript/Kotlin 共用向量和稳定错误码。
- 云 schema：追加 migration，验证授权索引、快照、增量与 Time Travel 恢复。
- 共享任务/UI：同时回归离线和联网 Android/Web。
- 联网 Android：CI 验证两个联网 Debug 变体；生产 Release 在本地完成测试、Lint、独立签名、Manifest/证书审计和 SHA-256，再由确认脚本上传公开 GitHub Release。
- 提交前运行 secrets 检查，禁止提交 `.dev.vars`、密钥、令牌、验证码、完整私人邮箱或任务正文。
