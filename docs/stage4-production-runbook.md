# 阶段四生产运行手册

当前仅使用 production，管理网页 `prod.rochelimit.me`、API `api.rochelimit.me`。staging 部署、三变体构建及旧域名迁移不再是发布前置条件；历史步骤见 Git。

## 环境与兼容

- 云端仅 production：管理网页 `https://prod.rochelimit.me`，API `https://api.rochelimit.me`；local 留作开发与隔离故障诊断。
- Android 仅 offline / production，保留生产包名、签名和本机存储身份；联网共用源码、测试仍在 `src/connected` / `src/testConnected`。
- staging 已退役：旧包不改指向 production，旧空间、会话和待上传数据不迁入正式环境。旧 D1、Worker 与 Access 回退配置保留，不合并或删除。配置移除不等于远端退役；实际 [切换记录](verification-records.md#production-2026-09-07) 与当前规则分开保存。
- 在 `cloud` / `cloud-web` 运行 `npm exec --no -- wrangler --version` 或 `npm exec --no -- wrangler whoami` 检查 CLI；仅报告认证结果，CLI 可用不代表远程操作获准。Node/PATH 见 [工具指南](windows-tooling.md)。

### 管理域名迁移（仅变更域名时）

先为 `prod.rochelimit.me` 准备 production Access 应用、精确管理员策略和 DNS；保留原有保护直到新入口通过验证。API 地址保持 `api.rochelimit.me`，将其 `ALLOWED_ORIGIN` secret 更新为 `https://prod.rochelimit.me`。API/Web 的 Access audience 必须对应新的正式管理应用；敏感配置仍保存在 secrets 中。

发布同一验证版本的 API/Web，核对网页路由、`MANAGEMENT_HOST`、API service binding 以及 Access 同源身份交换。新入口匿名 HTML、静态资源和代理 API 必须经过 Access；管理员登录后可进入任务库并刷新。旧域名停止服务或在受保护条件下引导到新入口，不能变成旁路。迁移前提醒使用者同步旧网页未上传的变更：IndexedDB 和会话属于旧源，不会随域名自动迁移。

## 推送与部署流程

1. 按 [AGENTS.md](../AGENTS.md) 执行。检查工作区、分支、远程和已有发布，只提交相关文件并添加 DCO sign-off；正常推送，不强制覆盖。固定完整 SHA，已有提交和运行不重复创建。
2. 仅执行 [最小测试方案](minimal-testing.md) 的活动集合；最终验证由同 SHA 的适用 CI 执行一次，不再本地全套后 CI 重跑。Web/CSS 改动不等待 Android CI。Cloud Connected 统一验证并生成 Web 产物，Dom Web Pages 复用；旧回归仅在正式截图或手动验收出问题后诊断。
3. 沿用当次明确的 production 发布授权，不重复询问；push-only 或模型偏好不授权生产变更/未来发布。部署前私下记录两个生产 D1 的 Time Travel bookmark、两个 Worker 当前版本和必要路由配置。生产 CI 会在迁移前读取这些信息并用本机持有的 RSA 公钥加密，只上传密文 artifact，保留 14 天。先在本机生成至少 3072 位 RSA 密钥对，将私钥保存在 Git 忽略的 `release-output/` 私有目录；将 DER/SPKI 公钥 Base64 作为 `recovery_public_key` 输入。私钥不进入 GitHub、命令正文或日志。下载归档后在本机验证解密并保存；归档失败会阻止生产变更。
4. 确认 main 仍指向已验证 SHA，再执行：

   ```text
   gh workflow run cloud-ci.yml --ref main -f target=production -f production_confirmation=DEPLOY_PRODUCTION -f release_commit=<完整SHA> -f recovery_public_key=<DER公钥Base64>
   ```

5. gate 优先复用同 SHA 成功的 verify 和未过期 Web 构建；复用时 verify 显示 skipped，这是已验证产物复用，不是未验证部署。无可用证据时只运行一次最小 verify。若 cloud-production 待审批，读取该 run 的 pending_deployments；当前账号有权限时沿用用户当次授权批准，保留保护规则。凭据不进入命令正文、日志或 Git。
6. 等待 deploy-production、迁移及 Access 检查成功，再完成管理员验收。失败时区分代码、权限和环境原因；未经授权不恢复/删除数据库。汇报 SHA、运行链接、实际环境和未完成项。

## 管理员页面加载验收

每次管理 Web 发布均关联实际部署 SHA：

- CI 在生产构建和严格 CSP 下模拟身份交换与初始化，验证任务库、刷新及无脚本异常；不能代替真实登录。
- 使用已有授权会话打开 `prod.rochelimit.me`，等待“已联网”和任务库可见，空列表须可操作。刷新后仍可进入，控制台无新增未捕获异常、CSP 或关键 JS 加载错误。
- 仅查看/刷新，不创建、修改或删除真实任务。原页有未保存输入时使用新标签。
- 记录时间、SHA、部署链接、会话来源及加载/刷新/控制台结果。无可用登录时标明“部署完成，管理员验收未完成”，不可用匿名 Access 302 替代。

2026-09-06 白屏源于 Ajv 的 `new Function` 被 CSP 拦截；`b002ab48d6496420a78f09175771ceabeb4efd55` 改用共享预编译校验器。保留动态代码禁用与启动回归，不添加 `unsafe-eval`。

## 受保护配置

GitHub Environment `cloud-production` 保留 required reviewer、`CLOUDFLARE_API_TOKEN`、`CLOUDFLARE_ACCOUNT_ID`。日常 token 使用发布所需 Workers/D1/DNS 权限；仅在管理 Access 时使用相应权限，不永久扩大 CI token。

| 组件 | Secrets |
| --- | --- |
| API Worker | AUTH_PEPPER、ADMIN_EMAIL、RESEND_API_KEY、ALLOWED_ORIGIN、ACCESS_TEAM_DOMAIN、ACCESS_AUD |
| Web Worker | ACCESS_TEAM_DOMAIN、ACCESS_AUD、MANAGEMENT_ADMIN_EMAIL |

ALLOWED_ORIGIN 对应正式管理源，API/Web audience 对应同一正式 Access 应用，精确管理员邮箱一致。密钥值、私人邮箱及可复用恢复凭据不进入公开记录。

Access self-hosted 应用保护完整管理主机名，仅允许精确邮箱和 One-time PIN，使用 24 小时会话、HttpOnly、Binding Cookie。禁止 Everyone/Bypass/域名通配和 Android 共享 service token。关闭 workers.dev/preview；Worker 在返回 HTML、JS、CSS 或代理 API 前验证 RS256、固定 issuer/JWKS、audience、有效期、签发时间及邮箱。缺配置 503，验证失败/公钥不可用 403，不触达业务资源；响应 no-store。

仅同源 `POST /v1/auth/access` 转发已验证 JWT；API 独立验证，只映射已有活动 ADMIN 成员，不创建账号或提权。保留 Bearer、HttpOnly refresh cookie、CSRF、认证限流、删除/隐私状态和敏感操作近期邮箱验证。Android/直接 API 使用应用认证，不加浏览器门禁。正式交换失败显示重试/退出，仅 local 回退邮箱登录；退出清理业务会话/缓存后进入 Access logout。

早期 `/sw.js` 停用脚本仍受 Access 保护：立即激活接管，只删 `dstationery-connected-` 静态缓存后注销；不接管 fetch、不清 IndexedDB/outbox、不强刷编辑页。须验证已有浏览器，不能以部署成功推断旧缓存已退出。

## Migration、恢复与故障处理

- migration 只追加；先迁移主 D1，再迁移独立删除账本，最后部署 API/Web。日常发布不重复创建库、密钥或初始环境。
- 优先回滚已记录 Worker；Time Travel 另获恢复授权。恢复主库后等待删除账本重放，确认删除内容未复活再开放写入。
- 故障演练使用 local/隔离数据与非敏感 canary，不对正式业务数据注入删除、恢复或并发故障。
- 域名变更及回滚须同时保持路由、API origin、Access audience、service binding 一致且受保护。先提示同步旧源 outbox；IndexedDB/会话不跨源迁移，新正式入口不得指向旧测试数据。

## 执行者 Android 发布

离线包名 `com.ds.localtaskmanager`，生产执行者 `com.ds.localtaskmanager.connected.production`。Release 分别使用仓库外 `local-task-manager-signing.properties`、`local-task-manager-connected-production-signing.properties`；密钥与密码分开备份。仅首次需要 `scripts/release/setup-release-signing.ps1` / `setup-executor-production-signing.ps1`，不重建已有签名身份。

默认只复用同 SHA 的成功 Android CI，不重复单元和截图。固定的干净 main 上通过 `scripts/release/prepare-release.ps1` / `prepare-executor-release.ps1` 准备候选，复用同源 CI/产物时不为调用脚本重复测试。核对版本/code、包名、环境、权限、签名和 SHA-256。默认 API 26/33/35 及真实设备验收含安装、登录、离线执行、恢复同步、升级；用户当次明确豁免时逐项写 waived 和原因，不能写 passed。

evidence 匹配本次 SHA/APK 且检查完成后才设 approved，使用 `publish-release.ps1` / `publish-executor-release.ps1 -EvidencePath <文件>`。标签为 `v<version>` / `executor-v<version>`，已有当次授权可传 ConfirmTag。发布公开 prerelease，附 APK、SHA-256、第三方声明、非敏感 evidence；不上传 mapping、密钥、密码、会话或恢复凭据。核对远端非 draft、附件摘要一致后报告完成。

## 新环境启用与事故恢复时的试运行（非常规发布门禁）

仅新环境启用或正式截图/用户手动验收暴露相关事故时执行以下专项，不随普通提交或每次发布重复。范围为管理员、其控制的测试执行者、真实执行者。先用非敏感数据验证登录/邀请、分配隔离、断网恢复、导出、删除计划撤销和立即删除；成员移除后旧会话/outbox 必须被拒绝。生产部署和 APK 发布不代表七天观察完成。

连续七天每日记录计数、request ID 和稳定错误码：无越权、丢失、重复生效、隐私泄漏或未解释 5xx；删除账本恢复有效，APK 摘要和签名固定。抽样日志无正文、邮箱、header、token、stack；监测 Worker/D1/邮件用量，70% 降频、90% 保护及邮件保留通道符合设计。

发现上述事故即停止真实数据写入，保留非敏感证据，在隔离环境修复；恢复后七天重新计时。范围不含公开注册、应用商店、远程推送、端到端加密、多管理员或大陆 SLA。
