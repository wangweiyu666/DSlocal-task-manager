# 阶段四生产运行手册

阶段四采用两道门。第一道门只完成仓库、staging 部署及恢复演练；第二道门必须在证据汇报后由管理者明确确认，才允许修改 production、发布执行者 APK或开始真实试运行。没有连续七天观察证据时，不得声明阶段四完成。

## 固定范围

- 生产环境共三个账号：管理者、管理者控制的执行者测试账号、真实执行者账号。
- 先用非敏感测试数据完成登录、邀请、权限隔离、断网同步、导出、计划删除、立即删除和恢复演练；通过后才录入真实任务。
- 执行者 APK 公开发布到 GitHub Releases；管理员网址不在仓库、README、Release、Issue、工作流日志或证据文件中记录。
- 管理端不是依靠隐藏 URL 保证安全。它必须置于 Cloudflare Access 自托管应用之后，只允许管理者邮箱，App Launcher 不显示，不允许 bypass policy；应用内仍保留 `ADMIN_EMAIL` 白名单和近期邮箱验证。
- 不包含公开注册、应用商店、远程推送、端到端加密、多管理员或大陆 SLA。

## 受保护配置

GitHub Environment `cloud-staging` 保存 staging Cloudflare 凭据。`cloud-production` 必须启用 required reviewer，并保存：

- `CLOUDFLARE_API_TOKEN`：最小权限包含 Workers、D1、DNS，以及 Access Apps and Policies Read；
- `CLOUDFLARE_ACCOUNT_ID`；
- `PRODUCTION_ADMIN_HOST`：只保存主机名，不含协议和路径；
- `PRODUCTION_ACCESS_APP_ID`：保护该主机名的 Cloudflare Access self-hosted application ID。

Production API Worker 另外保存 `AUTH_PEPPER`、`ADMIN_EMAIL`、`RESEND_API_KEY` 和 `ALLOWED_ORIGIN` secrets。`ALLOWED_ORIGIN` 必须等于 `https://` 加受保护管理员主机名。不要把这些值传入命令参数、文档或证据文件。

生产管理员主机名应使用从未提交到公共 Git 历史的新名称。部署工作流会在任何 migration 前通过 Cloudflare API 验证 Access 应用的域名、隐藏 App Launcher、唯一单邮箱 allow policy 和不存在 bypass policy；部署后还会验证未登录访问只能得到 302、401 或 403。

## 第一门：staging

1. 通过用户 `PATH` 直接确认 `node --version` 为 22+，再进入 `cloud`。
2. 使用 `npm exec wrangler -- d1 create dstationery-deletion-ledger-staging --location=apac` 创建独立账本；只把返回的数据库 ID写入 `cloud/wrangler.jsonc`。
3. 运行 `npm run guard:staging`、本地全量 migration、Cloud/Web/Android 测试，提交并推送固定 commit。
4. 手动运行 `Cloud Connected`，target 选择 `staging`。确认主库和账本 migration、API、Web 与健康检查成功。
5. 再手动运行同一工作流，target 选择 `staging-recovery`，输入 `RESTORE_STAGING`。该 job 会创建不含真实数据的 canary、记录主库 Time Travel bookmark、把删除事实写入独立账本、恢复主库，并等待 staging cron 重放账本。
6. 下载 `stage4-staging-recovery-<commit>` artifact。只有 `after-restore` 中 canary 为 1、`replay-status` 中为 0 且 cleanup 成功，恢复演练才通过。artifact 只包含 bookmark 的 SHA-256，不包含可复用 bookmark。

恢复会原地覆盖 staging 主库并取消在途查询。运行前必须停止 staging 人工操作；失败时由受保护工作流日志中的已遮罩值完成回退，不把 previous bookmark 放入公开 artifact。Cloudflare D1 Time Travel 的 bookmark 与恢复语义见[官方文档](https://developers.cloudflare.com/d1/reference/time-travel/)。

## 第二门：production

第一门通过后，汇报固定 commit、测试结果、staging deployment、恢复 artifact 和剩余风险。只有管理者再次明确确认后才执行：

1. 创建 `dstationery-deletion-ledger-production`，写入 production 账本 ID并重新运行两个环境 guard。
2. 配置新的受保护管理员主机名、Cloudflare Access 单邮箱 policy、生产邮件域和四个 Worker secrets。
3. 在 GitHub `cloud-production` 环境启用人工批准并配置受保护值。
4. 手动运行 `Cloud Connected`，target 选择 `production`，`production_confirmation` 输入 `DEPLOY_PRODUCTION`，`release_commit` 输入已通过 staging 的完整 commit SHA。
5. 验证 API 健康、管理端未登录被 Access 拦截、管理者登录和执行者 Android 均只连接 production。

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
