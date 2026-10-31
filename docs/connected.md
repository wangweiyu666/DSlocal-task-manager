# 联网版开发、同步与运维指南

> 当前仅 local 开发与 production 云端。发布、域名和恢复操作统一见 [生产运行手册](stage4-production-runbook.md)。

本文维护当前联网架构与同步行为，不作为历史阶段任务清单。完整任务业务语义以[需求总稿](preview.md)为准，机器契约以 `cloud/openapi.yaml`、`cloud/schemas/` 与 `cloud-protocol-test-vectors/` 为准。

## 产品与架构边界

| 组件 | 职责 | 存储与隔离 |
| --- | --- | --- |
| 联网 Android | 邮箱登录、邀请领取、空间任务、离线执行、同步和应用内通知 | 独立包名、签名、SQLCipher Room 与 Keystore 会话 |
| 管理员 Web | 成员、任务库、积分组、发布、结果、冲突和审计 | 独立构建、Cloudflare Access 管理员登录、IndexedDB 缓存与 outbox |
| API Worker + D1 | 鉴权、授权、版本、同步、结果、通知、审计和生命周期 | local/production 分主库、删除账本和配置，旧 staging 不参与发布 |

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
- 正式管理网页在返回资源或代理 API 前验证 Cloudflare Access 身份，会话设为 24 小时并绑定浏览器。网页通过 `/v1/auth/access` 自动换取现有管理员业务会话，无需第二次邮箱登录；API 独立校验 Access 签名、环境 audience、管理员邮箱及现有 ADMIN 成员，继续执行业务角色授权。
- 启动时，已有完整执行者身份、同步缓存且无已知账号限制的会话先展示本地任务，再后台校验和同步；首次登录仍等待远端确认。数据库初始化和过期文件清理在后台执行，进入任务页前先登记待处理的备份恢复。
- 临时网络或服务器故障保留账号、缓存和 outbox，允许继续离线使用；确认终止会话、成员撤销或删除时按同步状态清理，不能将临时网络故障当作清理依据。

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
- 单条命令的业务数据、change、通知、审计与回执位于同一 D1 batch；空间序列变化时回滚该命令事务，客户端可以安全重试。迁移文件按目录顺序应用，不以旧阶段号代替当前数据库状态。
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
- 体温询问使用执行定义 `{k:8}`，独立完成结果为 `executionKind:TEMPERATURE,status:COMPLETED,temperatureTenthsC`；体温用整数十分之一摄氏度（350～420）传输。仅整项完成上传答案，草稿、MISSED 与撤销不带体温。步骤答案使用同名字段，必须为已确认且执行类型为体温的步骤。云端依据提交修订和日期例外校验。
- 心情完成通过 `EXECUTION_EVENT` 的 `data` 发送 `executionKind:MOOD,status:COMPLETED,moodRating,moodText`；评分 1～5、文字最多 2000 码点。草稿、MISSED 与撤销不上传答案。
- 通知与单选完成仍用该事件：`NOTICE` 以完成记录已知晓，`CHOICE` 携带 `selectedOptionId`；客户端不提交积分。云端按任务修订及日期例外找到选项，保存名称、积分、通知正文和总分历史快照。
- STEPS 只在整项完成提交一次：`executionKind:STEPS,status:COMPLETED,stepResults`，按修订快照顺序列出所有步骤。状态为 `CONFIRMED / SKIPPED / NOT_APPLICABLE`；答案按类型为 counterValue、elapsedMillis、informationContent、moodRating/moodText、temperatureTenthsC 或 selectedOptionId。仅适用选做可跳过，跳过/不适用不带答案，直接或通知确认无输入答案。
- 云端重算分支与基础＋适用确认选项积分，拒绝伪造选择/分值、必需跳过和错误分支状态；补入 name、required、execution、taskName、taskDate 及选项/得分快照。Web 展示所选事件，不从最新任务或其他事件回填已撤销答案。撤销/MISSED 无 stepResults，不新增逐步云事件。
- 同一分配的首个有效终态结果暂定生效，后到结果作为 duplicate 保留；管理员改选写入新审计事件。

关键竞态结果：

| 场景 | 结果 |
| --- | --- |
| 管理员用过期基线编辑 | `TASK_VERSION_CONFLICT`，不自动合并 |
| 执行者提交已取消或旧修订任务 | 保存事实并标记 `needsReview` |
| 两台设备提交同一终态 | 先接受者暂定正式，后者保留为 duplicate |
| 批次中单条冲突 | 已成功命令不回滚，继续处理无依赖命令 |
| access token 过期 | 保留缓存/outbox并尝试 refresh |
| 确认会话终止 | 清理相关会话/空间缓存，要求重新登录；可重试网络错误不清理 |
| 联机确认成员被移除 | 清除空间缓存和 outbox，永久拒绝旧命令 |
| 新执行者加入 | 获得活动 `ALL` 任务，不获得既有 `SELECTED` 任务 |

## Android 交互

- “今日”不显示独立联网横条；同步复用离线导入按钮的共享悬浮位置和布局。
- 进入可用空间且页面可见时按服务端策略刷新通知（正常 15 秒），退到后台停止页面轮询；新未读通知显示应用内 Snackbar，不使用远程推送。
- “我的”左上角显示与设置按钮同尺寸的通知图标和未读角标，通知中心、同步状态与退出账号复用共享页面。
- 云端单任务载荷错误时隔离该任务并提示“云任务待修复”，其他本地任务和离线操作继续可用；后续收到正确修订后自动恢复。
- 首次快照、增量同步、网络恢复和手动同步均复用本地导入和执行逻辑。

## 稳定错误与安全

API 使用 Workers Rate Limiting binding，在任何 D1/删除账本查询前限流。production 按来源 IP（`CF-Connecting-IP`）设置所有请求 120 次/60 秒，登录验证码发送、验证、刷新及无 Bearer 请求再共用 20 次/60 秒的较低额度。伪造包名、User-Agent 或 Bearer 不会绕过所有请求的额度，登录接口也不会因带 Bearer 而免限流。缺少凭据的业务请求直接返回 401；CORS 预检不读取数据库。未认证客户端的 IP 可能多人共用，因此这些阈值需要结合实际 429 监测调整。

local 的两项阈值分别为 1200/120，便于串行 smoke 测试；local 与 production 使用独立的限流 namespace。超限返回 `429/RATE_LIMITED`、`Retry-After: 60` 及 JSON `retryAfterSeconds`。缺少限流绑定时返回 `503/SERVICE_UNAVAILABLE`，部署 guard 检查绑定配置和 namespace 隔离。原有邮箱/IP 验证码限额继续生效。

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

## 后台恢复与并发

Android 一次性 WorkManager 使用 CONNECTED 网络约束、30 秒指数退避和 3 分钟单次上限；服务端 Retry-After 截止另行持久化，前台不能绕过。Application 级 Engine/互斥锁协调前台与 Worker，事务提交后登记，启动从本地结果/撤销日志恢复漏登记命令。最后一条上传尚未返回时到达的新变更仍须获得后继执行机会；无待办且无显式请求时不额外拉取，离线变体相关桥接为空操作。

会话 generation 与账号、成员、空间一起核对，旧 Worker 不能操作或清除新会话。独立 ConnectedSyncDatabase v1→2 保留会话、队列、发送标记、游标及映射；主业务库为 Room 9。取消/超时/丢失响应保留原命令 JSON 和 ID，成功 duplicate 回执后原子清队列；拒绝/冲突保留诊断，不能无限重试。

后台补偿受系统调度和厂商自启动限制，不保证即时执行，force-stop 后需用户重开。仅在正式截图或手动验收出问题时按相关旧回归诊断，设备场景与实际证据分开：见 [后台对照](verification-records.md#background-2026-09-09) 和 [初期实现证据](verification-records.md#background-initial)。

## 环境与部署

API / Web / 两个 D1、Access、签名和生产发布操作只在 [运行手册](stage4-production-runbook.md) 维护。默认 Wrangler 为 local，production 远程操作需明确环境与授权；staging 退役数据不迁入正式环境。已应用 migration 只追加，故障注入只使用隔离数据。

协议变更更新 OpenAPI、Schema、共享向量及两端实现，受影响验证按 [最小测试规则](minimal-testing.md) 执行；不再为普通 PR 强制空库全迁移、三变体或完整回归。版本/阶段历史不是重新创建资源的指令。远程推送、DSTB1 云迁移、端到端加密、多管理员、多空间和公开注册仍不作为已实现能力。

### 每小时后台同步

联网 Android 为有效执行者会话登记唯一的 WorkManager 周期任务，周期一小时，首次延迟一小时；打开应用仍即时同步。无待上传数据时也主动拉取云端变化。要求有网络，复用三分钟执行上限、会话锁、限流冷却和指数退避。

登录进入空间及进程启动时恢复登记；UPDATE 保留原调度时间，避免每次打开应用重新倒计时。退出或清除失效会话时取消；账号切换更新身份与会话代次，旧任务不能访问新会话。已知账号限制不发起后台网络请求。离线变体不引入该调度或网络权限。

这是尽力而为的周期同步，Doze、省电、厂商后台限制和断网可能延迟运行；自启动权限不保证准点。没有新增精确闹钟或常驻前台服务。

### DOM 体温统计

联网管理端提供独立体温统计页，按执行者、任务和空间时区的自然日筛选（默认近 7 日，可选近 30 日或自定义）。仅提取每个实例当前正式完成结果中的体温，包括已确认步骤；更换正式结果或采用撤销结果后重新计算。图表按客户端提交时间 occurredAt 展示每次记录，步骤采用整项提交时间，不代表独立的测量时刻。任务与步骤按稳定 ID 分系列，并显示记录数、最新、最低、最高、平均值及明细。离线时显示已同步缓存与同步时间。

统计与结果复核以服务端同步序号判定正式选择的先后；snapshot 实体新增可选 `changeSequence`，增量同步保留原 `sequence`。这样同毫秒的多次改选也按实际服务端顺序展示，旧缓存缺失该字段时沿用更新时间排序。
