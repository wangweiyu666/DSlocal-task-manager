# Web production 部署记录：2026-10-31

## 版本与环境

- 部署源码：`260a8772a3ee8058dee932cd53a1b329042e31c1`（main）。体温功能提交为 `abcffb2423f43864096cd666a419911e77cd0738`；最终提交增加用户明确授权的生产恢复信息加密归档流程。
- 管理端：<https://prod.rochelimit.me>；配套 API：<https://api.rochelimit.me>。
- 离线 DOM：<https://wangweiyu666.github.io/DSlocal-task-manager/>。
- 本次不发布 Android、不安装设备、不修改真实任务。

## CI 与部署

- [最终提交验证](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/41216675971)：成功。
- [生产部署](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/41216897469)：成功，2026-10-31 23:53（Asia/Hong_Kong）。当前账号依据用户本次授权批准 `cloud-production`；环境保护规则保留。
- [同版本 Pages 部署](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/41216818687)：成功。生产 dispatch 完成后触发的第二次 Pages workflow 按既有 push-only 规则跳过，没有重复构建。
- 生产 gate 复用上述同 SHA 成功验证及未过期 Web 产物，因此部署运行的 `verify` 为 skipped；生产部署、迁移和 Access 检查均为 success。
- 两个生产 D1 的迁移检查均返回 `No migrations to apply`。API Worker 和管理 Web Worker 均部署成功；匿名页面、资源和 Web API 的 Access 拦截检查成功。
- 迁移前读取两个 D1 bookmark、两个 Worker 的现有部署及两个生产域名路由。在 runner 内进行 RSA-OAEP-SHA256 / AES-256-GCM 加密后上传密文 artifact，保留 14 天；私钥仅在本机 Git 忽略目录。归档已下载到本机并验证解密，SHA、数据库数、Worker 数及路由数匹配。公开记录不包含恢复值、私钥或 token。

## 实际验证计数

最终提交 CI 执行，而非静态统计：

| 集合 / 命令 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| `node --test scripts/ci/capture-production-recovery.test.mjs` | 4 | 0 | 0 |
| Cloud `npm run test:minimum` | 16 | 0 | 11 |
| Web `npm run test:minimum` | 24 | 0 | 0 |
| Web `npm run test:connected-smoke` | 1 | 0 | 0 |

Cloud 的 11 项为最小集合正则未选中的旧回归，不计入通过。Web、Cloud、cloud-web 类型检查通过；`npm run build:all` 的协议生成一致性、离线及联网 Web 构建和边界检查通过。CSP smoke 使用模拟服务，不代替以下真实管理员验收。本次没有扩展运行旧完整回归或 Android 测试。

## 真实管理员只读验收

- 时间：2026-10-31 23:54–23:55（Asia/Hong_Kong）。会话来源：用户已登录的 Codex 内置浏览器 `prod.rochelimit.me` 标签页。
- 刷新管理页后安全会话恢复，显示“已联网”；任务库和体温统计导航可用。
- 打开体温统计页，执行者默认选择、全部任务、近 7 日、空间时区、最近同步时间、汇总及空数据提示正常。
- 再次刷新后仍可进入任务库并重新打开体温统计页。23:54:25 起记录的控制台新增 warning / error 均为 0，没有新增 CSP、未捕获异常或关键 JS 加载错误。
- 一次自动等待返回 selector deadline；随即读取实际 DOM 确认目标已可见，刷新后页面运行正常。这是验收工具等待失败，不计为产品测试通过或页面失败。
- 本机截图：`release-output/web-deploy-2026-10-31-private/production-temperature-page.png`。不将包含账户显示名的截图上传公开仓库。
- 当前所选范围无正式体温结果；真实生产验收覆盖空状态、导航和刷新。多点曲线、系列、筛选、正式结果切换等由上述活动测试和模拟 CSP smoke 覆盖，未向生产写入演示数据。

## 实际发布操作

- 仅相关 Web / Cloud / 共享协议文件及加密归档流程以 DCO sign-off 提交，正常 `git push origin main`。
- 固定远端 main 为最终 SHA 后执行 `gh workflow run cloud-ci.yml --ref main -f target=production -f production_confirmation=DEPLOY_PRODUCTION -f release_commit=<最终SHA> -f recovery_public_key=<本机公钥Base64>`。
- 查看 `pending_deployments` 确认当前账号可审批，再按本次授权批准。使用已有同 SHA CI 证据，不重复触发验证运行。
- 使用 `gh run download` 下载指定密文 artifact，并在本机私下验证解密。未恢复或删除数据库，未改动 Access 策略。
- 本地 Android、提醒和其他未提交改动保留。本记录留在工作区，生产源码仍固定为上述 SHA。
