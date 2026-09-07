# 阶段四生产运行手册

> 2026-09-07 流程变更：按[单一云端环境](production-only.md)执行。staging 部署和恢复演练退出当前工作流；生产发布基于固定 SHA 的 CI 验证和既有人工环境保护。正式管理域名目标改为 `prod.rochelimit.me`，API 保持 `api.rochelimit.me`。下方旧域名、staging 门禁及历史发布记录不代表当前切换状态。

阶段四采用两道门。第一道门只完成仓库、staging 部署及恢复演练；第二道门必须在证据汇报后由管理者明确确认，才允许修改 production、发布执行者 APK或开始真实试运行。没有连续七天观察证据时，不得声明阶段四完成。

## 推送与部署流程

执行分工遵循 `AGENTS.md`。

1. 读取当前工作区、远程和分支，确定本次授权范围及固定完整 commit SHA。已有提交不重复提交；只提交本次相关文件并添加 DCO sign-off，正常推送，不强制覆盖远程。
2. 按[最小测试方案](minimal-testing.md)做本地针对性验证。对同一提交等待 `Cloud Connected`、`Android CI` 和 `Dom Web Pages` 的适用运行全部成功；只有推送成功不能报告部署完成。先查已有运行，避免重复触发。每次 Android 完整测试通过后，按该方案构建并交付已验证代码的 Debug APK。
3. 用户已明确授权本次 production 发布时，沿用该授权完成后续步骤，不重复询问。仅要求提交或推送时，说明 production 尚未更新，取得本次生产发布授权后继续。模型偏好本身不等于对所有未来生产发布的预先授权。
4. 按本手册记录生产迁移前的恢复信息，在 `main` 仍指向已验证 SHA 时，用 `gh workflow run cloud-ci.yml --ref main -f target=production -f production_confirmation=DEPLOY_PRODUCTION -f release_commit=<完整SHA>` 触发。分支已经变化时先核对新版本，不替换为未经 staging 验证的提交。
5. 等待 `verify` 通过；如 `cloud-production` 等待批准，查询该运行的 `pending_deployments`，在用户已授权且当前账号具有审批权限时，用 `gh api` 提交批准。保留环境保护规则；凭据不进入命令正文、日志或 Git。浏览器仅在登录授权或用户要求展示页面时使用。
6. 等待 `deploy-production` 与发布后门禁检查最终成功，并完成下文的“管理员页面加载验收”，再汇报固定 SHA、各运行链接与实际部署环境。工作流成功或 Access 302 跳转均不能单独代表管理网页可用。失败时查看对应日志，区分代码、权限和环境问题；未经授权不执行数据库恢复或破坏性操作。

以下阶段四范围和首次上线门禁仍适用。日常更新沿用已配置环境，不重复创建数据库、密钥或执行恢复演练。

## 管理员页面加载验收

每次管理 Web 发布均执行以下检查，并将结果关联到实际部署的完整 SHA。浏览器验收属于必要发布验证，可使用已有管理员会话；不导出 Cookie、Access JWT 或业务令牌，不向 CI 添加真实管理员凭据。

1. CI 使用联网生产构建，在与管理站点一致的 CSP 下加载页面，模拟成功的身份交换与业务初始化，确认任务库可见，且没有未捕获异常或 CSP 执行错误。该检查验证页面启动兼容性，不能冒充真实 Access 登录验收。
2. staging 部署后，以授权管理员身份进入 `test.rochelimit.me`。等待安全会话恢复完成，确认“任务库”和“已联网”出现；已有任务正常显示，无任务时显示可操作的空列表。刷新后仍能进入页面，控制台没有新增未捕获异常、动态执行代码被 CSP 拦截或关键 JS 加载失败。
3. production 部署后，对 `staging.rochelimit.me` 重复同样的只读检查。只查看列表和刷新，不为验收创建、修改或删除真实任务。旧标签页有未保存输入时，新建标签验证，避免刷新丢失输入。
4. 记录环境、完整 SHA、部署运行链接、验收时间、页面加载/刷新/控制台结果及是否使用已有会话。没有可用管理员登录时明确标记“部署已完成，管理员页面验收未完成”，不得以匿名跳转检查代替成功登录。

2026-09-06 的白屏回归由浏览器运行时 Ajv 编译触发：`new Function` 被 `script-src 'self'` 拦截，导致应用初始化失败。热修复 `b002ab48d6496420a78f09175771ceabeb4efd55` 改用共享预编译校验器；真实 production 管理员会话已验证任务库、刷新及控制台正常。后续须保留动态代码禁用回归和页面启动检查，不通过添加 `unsafe-eval` 或降低 Access 保护修复启动错误。

## 阶段四范围

- 生产环境共三个账号：管理者、管理者控制的执行者测试账号、真实执行者账号。
- 先用非敏感测试数据完成登录、邀请、权限隔离、断网同步、导出、计划删除、立即删除和恢复演练；通过后才录入真实任务。
- 执行者 APK 公开发布到 GitHub Releases。按管理者授权，生产管理入口改为 `staging.rochelimit.me`，测试入口改为 `test.rochelimit.me`；旧生产私密主机名仍不得写入公开记录。
- 两个管理入口均由 Cloudflare Access 保护整站，Web Worker 在返回 HTML、JS、CSS 或代理 API 前再次校验 Access JWT。网页通过 Access 后自动取得现有管理员账号的业务会话，不再重复邮箱登录；保留 `ADMIN_EMAIL` 白名单、空间角色授权及敏感操作的近期邮箱验证。
- 不包含公开注册、应用商店、远程推送、端到端加密、多管理员或大陆 SLA。

## 受保护配置

GitHub Environment `cloud-staging` 保存 staging Cloudflare 凭据。`cloud-production` 必须启用 required reviewer，并保存：

- `CLOUDFLARE_API_TOKEN`：日常发布只需要 Workers、D1 和 DNS 权限；首次配置或修改 Access 应用与身份策略，需要另外的 Access 管理权限，不必永久扩大 CI token 权限；
- `CLOUDFLARE_ACCOUNT_ID`。

Production API Worker 另外保存 `AUTH_PEPPER`、`ADMIN_EMAIL`、`RESEND_API_KEY` 和 `ALLOWED_ORIGIN` secrets。本次迁移将 `ALLOWED_ORIGIN` 设为 `https://staging.rochelimit.me`；staging API 配置为 `https://test.rochelimit.me`。其余秘密值不得写入命令正文、文档或证据。两个 API 的域名、应用认证与限流保持不变，不添加 Access 浏览器门禁。

两个 Web Worker 均配置 `ACCESS_TEAM_DOMAIN`（仅 `团队名.cloudflareaccess.com`）、`ACCESS_AUD`（各自应用的 64 位十六进制 audience）和 `MANAGEMENT_ADMIN_EMAIL` secrets。邮箱须与各自 API 的管理员白名单一致；两个应用 audience 必须不同，不能复用。各 API Worker 同时配置与自己 Web 相同的 `ACCESS_TEAM_DOMAIN`、`ACCESS_AUD` secrets，以独立验证身份交换。旧 `MANAGEMENT_GATE_SECRET` 与 GitHub `PRODUCTION_ADMIN_HOST` 不再被代码或 CI 使用，可在迁移验收后按凭据清理流程移除。

Access 使用两个独立的 self-hosted 应用，分别覆盖两个完整主机名（不限定路径）。仅允许现有管理员的精确邮箱，使用 One-time PIN；不要加入 Everyone、Bypass、通配邮箱域或面向 APK 的共享 service token。建议会话有效期 24 小时。首次开通 Zero Trust 的套餐与付款信息由账号所有者确认；免费方案不代表无需开通流程。

2026-09-05 当前账号的开通页显示 Free 为 $0、最多 50 席位，但要求付款资料、账单地址、服务条款以及对超出免费额度的用量按月扣费的授权。选择 Free 不代表已完成开通；由账号所有者自行审阅并完成 `Activate Zero Trust Free` 后，再创建 Access 应用和切换域名。

2026-09-05 账号所有者已完成 Zero Trust Free 开通，One-time PIN 已添加，两个管理主机名的独立 Access 应用均已创建，限制为各自现有管理员精确邮箱、24 小时会话，并启用 HTTP Only 和 Binding Cookie。管理者已明确批准先保护仍指向 staging 服务的 `staging.rochelimit.me`，再将其切换到 production。网页域名与 Worker 的实际部署状态以发布运行及验收记录为准，不能将创建 Access 应用视为 Worker 已部署。

边缘先拦截未登录请求；Worker 使用固定 issuer 的 JWKS 验证 RS256 签名、audience、有效期、签发时间和管理员邮箱，拒绝伪造邮箱头、旧门禁 cookie 及其他主机名。配置缺失时 503，JWT 验证失败或公钥不可用时 403，均不触达静态资源或业务 API。两个环境关闭 workers.dev 与 preview URLs，所有资源经 Worker 执行且 `no-store`。网页退出在应用会话与本地缓存清理后导航到 Access logout。

网页初始化调用 `POST /v1/auth/access`，仅此同源请求转发已验证的 Access JWT。API 再次验证固定 issuer、audience、时间和 `ADMIN_EMAIL`，只映射已有账号的活动 ADMIN 成员，不创建账号或提升角色；删除恢复期账号只能进入既有恢复流程，业务冻结规则继续执行。交换保留业务 Bearer、HttpOnly refresh cookie、CSRF 与认证限流，不把 Access 登录视为敏感操作验证。Android 仍使用原有邮箱登录；仅 local 环境回退到网页邮箱登录，正式环境交换失败显示重试/退出入口。

部署此单次登录变更前，先为 staging API 配置对应 Access secrets，完成测试环境真实管理员自动进入验证；再为 production API 配置其独立 audience 并发布同一 SHA。CI 在 migration 前检查这些 secret 名称。回退 API 与 Web 到兼容版本时保留 Access 配置和边缘策略，不降低门禁。

早期联网版曾注册 `/sw.js`。仅停止生成 Service Worker 不会注销既有浏览器注册，旧缓存可能持续显示已移除的登录页。Web Worker 在 Access 校验后为 `/sw.js` 返回停用脚本：立即激活并接管旧注册，只删除 `dstationery-connected-` 前缀的静态缓存，然后注销；不接管 fetch、不清理 IndexedDB 或 outbox、不强制刷新正在编辑的页面。浏览器检查更新后，下次刷新取得最新工作台。验收需同时核对已登录旧浏览器与无会话请求，不能仅凭服务器发布成功判断旧页面已退出。

## 本次域名与 Access 迁移顺序

以下是实施步骤，不代表云端已经完成迁移。完成后另行记录固定 SHA、运行结果和登录验收。

1. 盘点 Zero Trust 组织、套餐、Access 应用、精确管理员邮箱及当前 Worker 自定义域映射。权限不足时由账号所有者登录控制台；不为读取配置输出令牌或完整邮箱。
2. 保留旧生产入口和当前 Worker 版本作为回退信息。先为 `test.rochelimit.me` 与 `staging.rochelimit.me` 建立 Access 整站策略，确认没有更具体路径的放行策略；配置两个 Web Worker 的独立 Access secrets。先保护旧 staging 主机名，再转移它的用途。
3. 提醒管理者先同步旧 staging 页面尚未上传的修改并关闭旧标签页。新域名不会自动搬迁旧源的 IndexedDB/未同步命令；数据库内容仍在 staging D1，不复制进 production。
4. 主代理提交并推送固定 SHA，等待自动 staging 部署，将 staging Web 移到 `test.rochelimit.me` 并更新对应 API origin。检查测试入口 Access 跳转、管理员登录和 staging 服务绑定，确认旧 staging 域已从 staging Worker 解除。
5. 记录生产两个 D1 的恢复 bookmark 与两个 Worker 版本；更新生产 API 的 `ALLOWED_ORIGIN`，然后按本次已获授权的流程发布相同 SHA。生产 Web 绑定 `staging.rochelimit.me`；核对旧生产域不再路由至该 Worker，不为旧域新增公开绕过入口。
6. 验证两个站点根页面、静态资源和 `/v1/bootstrap` 均被 Access 拦截；验证管理员登录后可正常使用各自数据、非管理员不能进入，以及两个直接 API 仍返回应用认证响应、Android 地址不变。CI 的 `verify-access.mjs` 只验证匿名拦截，不能代替管理员登录成功验证。
7. 回退时同时恢复 Worker 版本、域名映射和 API origin，维持 Access 保护。不能只回滚代码而留下错配的域名；新生产主机名不允许回指 staging 并继续用于生产操作。

## 第一门：staging

1. 通过用户 `PATH` 直接确认 `node --version` 为 22+，再进入 `cloud`。
2. 使用 `npm exec wrangler -- d1 create dstationery-deletion-ledger-staging --location=apac` 创建独立账本；只把返回的数据库 ID写入 `cloud/wrangler.jsonc`。
3. 运行 `npm run guard:staging`、本地全量 migration、Cloud/Web/Android 测试，提交并推送固定 commit。`main` push 的 `Cloud Connected` 验证全部通过后会自动部署 staging；PR 只验证、不部署。必要时仍可手动运行工作流并选择 `staging` 重新部署。
4. 确认自动 `deploy-staging` job 中主库和账本 migration、API 与 Web 部署成功。
5. 再手动运行同一工作流，target 选择 `staging-recovery`，输入 `RESTORE_STAGING`。该 job 会创建不含真实数据的 canary、记录主库 Time Travel bookmark、把删除事实写入独立账本、恢复主库，并等待 staging cron 重放账本。
6. 下载 `stage4-staging-recovery-<commit>` artifact。只有 `after-restore` 中 canary 为 1、`replay-status` 中为 0 且 cleanup 成功，恢复演练才通过。artifact 只包含 bookmark 的 SHA-256，不包含可复用 bookmark。

恢复会原地覆盖 staging 主库并取消在途查询。运行前必须停止 staging 人工操作；失败时由受保护工作流日志中的已遮罩值完成回退，不把 previous bookmark 放入公开 artifact。Cloudflare D1 Time Travel 的 bookmark 与恢复语义见[官方文档](https://developers.cloudflare.com/d1/reference/time-travel/)。

## 第二门：production

第一门通过后，汇报固定 commit、测试结果、staging deployment、恢复 artifact 和剩余风险。只有管理者再次明确确认后才执行：

1. 创建 `dstationery-deletion-ledger-production`，写入 production 账本 ID并重新运行两个环境 guard。
2. 配置受 Access 保护的管理员主机名、生产邮件域、API Worker 业务 secrets 与 Access issuer/audience，以及 Web Worker 的 Access issuer、独立 audience 和单一管理者邮箱。
3. 在 GitHub `cloud-production` 环境启用人工批准并配置受保护值。
4. 手动运行 `Cloud Connected`，target 选择 `production`，`production_confirmation` 输入 `DEPLOY_PRODUCTION`，`release_commit` 输入已通过 staging 的完整 commit SHA。
5. 验证管理端未登录不能读取任何静态资源或代理 API、非白名单身份不能进入、管理者完成 Access 后自动进入工作台且退出会离开 Access 会话，并确认管理者 Web 和执行者 Android 均只连接 production。

Production migration 前记录 D1 bookmark 和当前 Worker version。故障先回滚 Worker 固定版本；若兼容 migration 仍有问题，再按 bookmark 执行 Time Travel。恢复主库后必须等待删除账本重放完毕再开放写入。

## 执行者 Android 发布

生产包名固定为 `com.ds.localtaskmanager.connected.production`，使用仓库外 `local-task-manager-connected-production-signing.properties`。首次创建密钥时运行 PowerShell 脚本；这里使用 `pwsh` 是因为脚本需要安全随机数、对象化 JSON 和 Windows 文件 API：

```powershell
pwsh -NoProfile -File .\scripts\release\setup-executor-production-signing.ps1
```

把 keystore 和 properties 分开离线备份。随后在干净的 `main` 上准备候选：

```powershell
pwsh -NoProfile -File .\scripts\release\prepare-executor-release.ps1
```

在 API 26/33/35 与真实执行者设备完成安装、登录、离线执行、恢复同步和升级测试，把 `release-evidence.json` 对应检查改为 `passed`。确认 commit、APK SHA-256 和签名证书后，将 evidence 状态改为 `approved`，再运行：

```powershell
pwsh -NoProfile -File .\scripts\release\publish-executor-release.ps1 -EvidencePath <release-evidence.json>
```

脚本要求输入 `executor-v<version>`，创建公开 prerelease，并上传 APK、SHA-256、第三方声明和不含敏感内容的 evidence。不得把 mapping 文件、keystore、密码或管理员网址上传 GitHub。

## 三账号真实试运行与七天观察

演练通过后，管理者创建空间，先邀请管理者控制的执行者测试账号，再邀请真实执行者账号。两个执行者必须验证只能读取自己的分配、导出自己的数据；移除后旧会话和离线 outbox 必须被拒绝。

连续七天每天记录以下结果，只记录计数、request ID 和稳定错误码，不记录邮箱、任务正文、验证码、令牌或管理员网址：

| 检查 | 通过标准 |
| --- | --- |
| 权限与隐私 | 零跨账号/跨空间读取，零隐私门禁绕过 |
| 数据可靠性 | 零任务或执行结果丢失，outbox 重试无重复生效 |
| 服务错误 | 零未解释 5xx；所有失败均有安全 request ID 与稳定错误码 |
| 日志 | 抽样仅含结构化白名单字段，零正文、邮箱、header、token 或 stack |
| 删除与恢复 | 计划删除可撤销、立即删除不可恢复、恢复后账本重放有效 |
| 配额 | 正常低于 70%；若触发 70%/90%，降频与保护模式符合设计 |
| 分发 | GitHub APK SHA-256 与安装文件一致，签名证书摘要保持固定 |

任一天出现数据丢失、越权、隐私泄漏、敏感日志或无法解释的 5xx，立即停止真实数据写入、保留非敏感证据并回到 staging；修复后七天计时重新开始。
