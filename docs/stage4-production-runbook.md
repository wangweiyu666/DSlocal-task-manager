# 阶段四生产运行手册

阶段四采用两道门。第一道门只完成仓库、staging 部署及恢复演练；第二道门必须在证据汇报后由管理者明确确认，才允许修改 production、发布执行者 APK或开始真实试运行。没有连续七天观察证据时，不得声明阶段四完成。

## Luna 推送与部署流程

本项目的推送与部署工作默认交给 Luna（`gpt-5.6-luna`）执行。负责范围包括 Git 提交和推送、等待 CI、确认 staging、在已获 production 发布授权后触发和审批生产工作流，以及核验最终部署结果。主代理负责转达结果；遇到凭据失效、权限拒绝或需要改变发布范围的问题时再报告用户。

1. 读取当前工作区、远程和分支，确定本次授权范围及固定完整 commit SHA。已有提交不重复提交；只提交本次相关文件并添加 DCO sign-off，正常推送，不强制覆盖远程。
2. 按[最小测试方案](minimal-testing.md)做本地针对性验证。对同一提交等待 `Cloud Connected`（含 `deploy-staging`）、`Android CI` 和 `Dom Web Pages` 的适用运行全部成功；只有推送成功不能报告部署完成。先查已有运行，避免重复触发。每次 Android 完整测试通过后，由 Luna 按该方案构建并交付已验证代码的 Debug APK。
3. 用户已明确授权本次 production 发布时，沿用该授权完成后续步骤，不重复询问。仅要求提交或推送时，说明 production 尚未更新，取得本次生产发布授权后继续。模型偏好本身不等于对所有未来生产发布的预先授权。
4. 按本手册记录生产迁移前的恢复信息，在 `main` 仍指向已验证 SHA 时，用 `gh workflow run cloud-ci.yml --ref main -f target=production -f production_confirmation=DEPLOY_PRODUCTION -f release_commit=<完整SHA>` 触发。分支已经变化时先核对新版本，不替换为未经 staging 验证的提交。
5. 等待 `verify` 通过；如 `cloud-production` 等待批准，查询该运行的 `pending_deployments`，在用户已授权且当前账号具有审批权限时，用 `gh api` 提交批准。保留环境保护规则；凭据不进入命令正文、日志或 Git。浏览器仅在登录授权或用户要求展示页面时使用。
6. 等待 `deploy-production` 与发布后门禁检查最终成功，再汇报固定 SHA、各运行链接与实际部署环境。失败时查看对应日志，区分代码、权限和环境问题；未经授权不执行数据库恢复或破坏性操作。

以下阶段四范围和首次上线门禁仍适用。日常更新沿用已配置环境，不重复创建数据库、密钥或执行恢复演练。

## 阶段四范围

- 生产环境共三个账号：管理者、管理者控制的执行者测试账号、真实执行者账号。
- 先用非敏感测试数据完成登录、邀请、权限隔离、断网同步、导出、计划删除、立即删除和恢复演练；通过后才录入真实任务。
- 执行者 APK 公开发布到 GitHub Releases；管理员网址不在仓库、README、Release、Issue、工作流日志或证据文件中记录。
- 管理端不是依靠隐藏 URL 保证安全。Production Web Worker 在返回 HTML、JS、CSS 或代理 API 前执行应用层单邮箱白名单和邮箱验证码门禁；应用内仍保留 `ADMIN_EMAIL` 白名单、空间角色授权和近期邮箱验证。
- 不包含公开注册、应用商店、远程推送、端到端加密、多管理员或大陆 SLA。

## 受保护配置

GitHub Environment `cloud-staging` 保存 staging Cloudflare 凭据。`cloud-production` 必须启用 required reviewer，并保存：

- `CLOUDFLARE_API_TOKEN`：最小权限只包含本工作流所需的 Workers、D1 和 DNS，不需要 Zero Trust/Access 或付费订阅权限；
- `CLOUDFLARE_ACCOUNT_ID`；
- `PRODUCTION_ADMIN_HOST`：只保存主机名，不含协议和路径。

Production API Worker 另外保存 `AUTH_PEPPER`、`ADMIN_EMAIL`、`RESEND_API_KEY` 和 `ALLOWED_ORIGIN` secrets。`ALLOWED_ORIGIN` 必须等于 `https://` 加受保护管理员主机名。不要把这些值传入命令参数、文档或证据文件。

Production Web Worker 另外保存 `MANAGEMENT_GATE_SECRET` 和 `MANAGEMENT_ADMIN_EMAIL` secrets。前者必须是至少 32 字节的独立高熵随机值；后者必须与 API Worker 的 `ADMIN_EMAIL` 完全一致。二者不得提交到仓库或写入 GitHub 日志。

生产管理员主机名应使用从未提交到公共 Git 历史的新名称。应用门禁只使用现有 Workers、服务绑定和 HMAC cookie，不启用 Cloudflare Zero Trust/Access，不要求付款方式。未验证请求只得到无脚本的最小验证码页，不会读取前端静态资源或调用业务 API；白名单外邮箱不会触发邮件。门禁 cookie 最长 30 天、`HttpOnly; Secure; SameSite=Strict`、绑定浏览器 User-Agent；缺少配置时 Worker 以 503 失败关闭。生产联网 Web 不注册 Service Worker，所有受保护响应均 `no-store`，避免离线缓存绕过门禁。部署工作流会在任何 migration 前验证两个 Web Worker secrets 存在，部署后验证未登录根页面和常见静态资源都返回 401 及门禁标记。

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
2. 配置新的受保护管理员主机名、生产邮件域、四个 API Worker secrets，以及 Web Worker 的应用门禁密钥和单一管理者邮箱。
3. 在 GitHub `cloud-production` 环境启用人工批准并配置受保护值。
4. 手动运行 `Cloud Connected`，target 选择 `production`，`production_confirmation` 输入 `DEPLOY_PRODUCTION`，`release_commit` 输入已通过 staging 的完整 commit SHA。
5. 验证管理端未登录不能读取任何静态资源或代理 API、非白名单邮箱不发信、管理者验证后可进入且退出会清除门禁，并确认管理者 Web 和执行者 Android 均只连接 production。

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
