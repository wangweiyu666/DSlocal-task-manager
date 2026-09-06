# 最小测试方案

日常按改动选择一组测试，通过后停止；只有出现新改动、失败或未覆盖风险才扩大范围。本方案针对同步修复的快速反馈，不代表所有产品功能已经验证。Web／Cloud 的 `npm test` 继续运行各自完整套件。Android 默认 CI 在 connected 变体执行一次全部共用及联网单元测试，保留三个变体的构建、Lint、离线网络边界和截图检查；发布继续遵循对应主指南。

## Android 去除重复执行

staging（connected）和 production 使用同一份联网源码及联网测试；三个变体共用 `src/test` 的业务测试。心情功能完成后的基线为：共用测试 125 条，联网专属测试 12 条。默认 CI 只执行 `testConnectedDebugUnitTest`，从原来的 `125 + 137 + 137 = 399` 次降至 **137 次**，减少 262 次重复执行（约 66%），保留全部 137 条独立单元用例。没有删除测试或添加 skip。

```text
.\gradlew.bat testConnectedDebugUnitTest --no-daemon
```

日常修改继续使用 `--tests` 只跑受影响类；单纯替换心情图标只检查资源构建、相关界面及截图，不重跑数据库、协议和三个变体的完整业务测试。备份、数据库迁移、提交并发和权限等独立回归保留。

三个变体仍分别执行 `lintOfflineRelease`、`lintConnectedDebug`、`lintProductionDebug` 及 Debug 构建；离线版本继续验证无网络权限／网络客户端依赖。截图仍仅在 offline 运行，设备测试按受影响交互选择。

只有明确要求完整矩阵，或变体源码、source set、环境地址、签名、构建配置等变化需要验证差异时，才增加受影响变体测试。手动触发 Android CI 可勾选 `full_android_matrix`，恢复三变体全部单元测试；日常 push／PR 默认不重复运行。独立发布指南要求的生产验证仍然执行。

## Luna 测试分工

简单测试的编写，以及测试命令的执行、等待和结果整理，默认交给 Luna（`gpt-5.6-luna`）。简单测试包括行为和预期结果已明确的单元测试、参数校验、错误响应以及已有故障的直接回归测试。主代理提供待验证行为、相关文件、允许修改的范围及验收条件，并审查新增断言是否能发现真实回归。

涉及并发、跨端状态一致性、安全边界或数据库恢复等复杂测试，先由主代理确定测试设计，仍可由 Luna 执行确定后的方案。遵循最小测试原则，优先复用既有测试，避免重复或只照抄实现的断言。

Luna 应报告实际运行命令、代码版本或相关工作区变更、通过/失败/跳过数量、关键失败原因和未覆盖范围。不得为获得通过结果而跳过失败用例或削弱断言；无法定位的问题及时交回主代理。模型不可用时明确说明，不能把其他模型的执行称为 Luna。完整 Android 测试通过后，继续按下文交付 Debug APK。

## 保留哪些测试

| 故障或行为 | 最小覆盖位置 | 保留理由 |
| --- | --- | --- |
| 失败回执重放被误判成功 | Cloud `sync-recovery.test.ts` | 拒绝和冲突重放必须保持失败状态 |
| 命令部分提交或重复提交 | 同上 | 回执写入失败回滚、过期规划、并发重复是三个独立故障场景 |
| 并发刷新令牌相互失效 | 同上 | 并发请求及丢失响应后的重试必须得到同一有效令牌 |
| 云端绕过完整协议校验 | 同上及 `shared-protocol.test.ts` | 分别验证缺字段不落库、Schema 合法但日期不存在时仍拒绝 |
| Web 重渲染导致连续重试 | Web `connected-sync-scheduler.test.ts` | 保留待重试命令、网络异常、手动并发及离线取消的三条测试 |
| 完成后撤销不能正确同步 | Cloud 撤销流程、Web `connected-results.test.ts`、Android 下列两类 | 分别检查服务端结果选择、旧内容不回显、同步回调与撤销载荷 |

Cloud 数据测试继续使用加载真实 migration 的内存 SQLite，验证业务表、通知、审计与回执；本地 Worker HTTP smoke 负责补充真实 D1/Worker 运行环境验证。

## 日常命令

从仓库根目录运行，只选择实际改动的端。历史精简基线为 Cloud 8、Web 7、Android 10 条；新增独立回归后数量会变化，以测试报告为准，不能为了维持旧条数删除必要断言。

Cloud 同步、回执、刷新和协议入口修改：

```text
npm --prefix cloud run test:minimum
```

Web 同步调度和结果展示修改：

```text
npm --prefix web run test:minimum
```

Android 同步回调、运行时判断或结果载荷修改：

```text
java --version
adb version
.\gradlew.bat testConnectedDebugUnitTest --tests "com.ds.localtaskmanager.ui.execution.W22ExecutionViewModelTest" --tests "com.ds.localtaskmanager.connected.ConnectedRuntimeTest" --no-daemon
```

要求 JDK 17、Android SDK。上述是 JVM 单元测试，不要求启动模拟器。缓存受限或离线运行方式见[Windows 工具指南](windows-tooling.md#使用已缓存的-robolectric-运行离线单元测试)，其中 Gradle 任务也应替换为上面的筛选命令。

TypeScript 生产代码修改后，额外运行对应端的 `npm --prefix cloud run typecheck` 或 `npm --prefix web run typecheck`。这些静态检查不计入测试条数。

## Access 门禁与域名迁移的最小验证

由主代理设计安全边界，Luna 编写并运行 `cloud-web/tests/gate.test.ts`：用临时真实 RSA 密钥与模拟 JWKS 覆盖合法身份、伪造/过期/跨环境令牌、缺配置、旁路主机、跨站请求和公钥获取失败；不模拟 `jwtVerify` 的成功结果。通过数据驱动分组保留约 6 条测试，不为每条路径重复建立测试。Web 的 `connected-api-access.test.ts` 补充 Access 登录 HTML 不能当成有效业务会话，以及正常响应和退出标记。

```text
npm --prefix cloud-web run check
node cloud/scripts/guard-environment.mjs staging
node cloud/scripts/guard-environment.mjs production
npm --prefix web test
npm --prefix web run build:all
```

远程部署后分别运行 `node cloud-web/scripts/verify-access.mjs test.rochelimit.me` 和 `node cloud-web/scripts/verify-access.mjs staging.rochelimit.me`，验证匿名访问页面、资源与代理 API 都跳转 Access。此检查只证明边缘拦截；还须记录管理员登录成功、非管理员拒绝、两个网站各自连接正确环境，以及直接 API 仍使用应用认证的结果。本次没有 Android 代码/地址修改，不因网页门禁重复本地完整 Android 测试；若 CI 执行完整 Android 测试，则仍按下节交付 APK。

网页单次 Access 登录追加最小覆盖：API 用真实 RSA/JWKS 验证身份交换成功、无效/跨环境令牌、同源约束、账号和成员状态、同身份会话复用、CSRF 及敏感操作未自动授权；Web 覆盖自动交换、仅 local 回退和 Access 失败不回退；代理只向精确交换路径转发 JWT。身份会话与数据库授权发生变化时运行 Cloud 全套和既有 HTTP smoke，网页运行完整测试与构建，远程验证两个环境通过 Access 后直接进入工作台及匿名 API 拒绝。Android 源码未变，不重复本地完整测试。

## 完整 Android 测试后的 Debug APK

API 入口限流及 Android 重试修改的最小追加验证为 `cloud/tests/request-guard.test.ts`（4 条）和 `CloudApiRateLimitTest`（3 条）。检查超限与缺凭据时不访问数据库、伪造客户端标识不豁免限流，以及客户端等待期间不再次联网；部署前再运行本地 HTTP smoke，验证真实 binding 与原有登录/同步兼容。

每次 Android 完整测试通过后，默认由 Luna（`gpt-5.6-luna`）继续构建本次测试覆盖变体的 Debug APK，并提供可点击的本地文件路径、对应环境和 SHA-256。完整三变体测试通过时构建 offline、connected（staging）和 production 三个 Debug APK；仅执行筛选测试时不触发该步骤。若模型不可用，明确说明，不把其他模型的执行称为 Luna。

构建使用通过测试的同一份代码；检查测试后的代码差异。只有文档修改时可以沿用测试结果，影响 Android 的代码或构建配置变化则补充相应验证。不为打包而重复已经通过的完整测试，也不把失败或中断的测试视为通过。

```text
java --version
adb version
.\gradlew.bat assembleOfflineDebug assembleConnectedDebug assembleProductionDebug --no-daemon
```

本机缓存齐备且网络受限时可加 `--offline`。默认生成 `app/build/outputs/apk/<variant>/debug/app-<variant>-debug.apk`，核对包名与构建产物，避免把旧 APK 当成本次结果。已有同一代码的已验证 Debug 构建产物时可以复用，不必重复构建。

这里交付本地 Debug 包，联网 production-debug 连接生产服务，但与正式 production Release 是不同安装包。公开上传、Release 签名发布及安装到设备分别按用户授权执行。完整 Android CI 的现有构建仍由 Gradle 执行；Luna 负责跟进结果并交付对应 APK。

## 实际删减

- Cloud `shared-protocol.test.ts` 从 31 条降到 1 条：删除重复的完整向量遍历和字段组合，保留 Worker 语义校验接线检查。合法载荷已有 `contract.test.ts`，缺字段拒绝已有数据库回归；完整 TypeScript 协议向量由 Web `protocol.test.ts` 维护。Android 使用独立 Kotlin 解析器，继续保留其向量测试。
- Cloud 事务故障注入从 3 个位置减为最后的回执写入失败，共同断言此前业务、通知、审计、同步投影均回滚。减少中途写入点的单独故障覆盖；若以后拆分事务边界，需要恢复对应故障测试。
- 上次精简将 Cloud 全套由 51 条降为 19 条；本次新增 4 条 API 入口限流回归后为 **23 条**。未把已删除用例隐藏到循环中，也不通过 `skip` 降低计数。
- Android 日常同步回归使用 connected 的相关类筛选；默认 CI 从心情功能后的 399 次三变体执行缩到 137 次，保留全部独立用例。历史基线的 10 条同步测试会随新增独立回归增加。Web 保留独立用例，日常按影响范围筛选。

## 什么时候扩大验证

| 改动范围 | 额外验证 |
| --- | --- |
| Schema、共享协议实现或生成器 | Web 完整协议向量、`npm --prefix web run verify:protocol`、Android 对应解析器测试和 Cloud 校验入口 |
| D1 migration、事务、查询或授权 | Cloud 全套，以及按联网指南运行本地 migration 和 Worker HTTP smoke |
| Room Schema、持久化队列 | Android 对应数据库/迁移测试，不能仅运行以上两类 |
| 共享业务/UI | 受影响类在 connected 测一次；CI 执行 connected 全套及三变体构建／Lint，界面按需补截图或设备测试 |
| 变体源码、source set、环境或构建配置 | 增加受影响变体测试；需要完整矩阵时手动勾选 `full_android_matrix` |
| Web 构建、依赖或资源边界 | `npm --prefix web run build:all` 和受影响模块测试 |
| 系统权限、通知、后台行为或设备适配 | 按模拟器测试指南选择受影响 API 的设备测试 |
| 发布 | 执行既有 CI 和发布指南中的完整门禁、签名及恢复验证 |

纯文档改动检查链接和 `git diff --check` 即可；只精简测试时运行被修改的套件并核对条数，不重跑无关的 Android 全矩阵。测试通过后不重复执行相同检查。
