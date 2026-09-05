# 最小测试方案

日常按改动选择一组测试，通过后停止；只有出现新改动、失败或未覆盖风险才扩大范围。本方案针对同步修复的快速反馈，不代表所有产品功能已经验证。`npm test` 和 CI 仍运行各模块完整套件，发布继续遵循对应主指南。

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

从仓库根目录运行，只选择实际改动的端。三个命令组全部运行共 **25 条**：Cloud 8、Web 7、Android 10。数量是本次精简后的基线，新增独立行为时可以增加。

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

## 实际删减

- Cloud `shared-protocol.test.ts` 从 31 条降到 1 条：删除重复的完整向量遍历和字段组合，保留 Worker 语义校验接线检查。合法载荷已有 `contract.test.ts`，缺字段拒绝已有数据库回归；完整 TypeScript 协议向量由 Web `protocol.test.ts` 维护。Android 使用独立 Kotlin 解析器，继续保留其向量测试。
- Cloud 事务故障注入从 3 个位置减为最后的回执写入失败，共同断言此前业务、通知、审计、同步投影均回滚。减少中途写入点的单独故障覆盖；若以后拆分事务边界，需要恢复对应故障测试。
- Cloud 全套由 51 条降为 **19 条**；未把已删除用例隐藏到循环中，也不通过 `skip` 降低计数。
- Android 日常同步回归从三变体共 358 次执行缩到 connected 的两类共 **10 次**；不删除独立功能测试。Web 保留 52 条独立用例，日常只选相关的 7 条。

## 什么时候扩大验证

| 改动范围 | 额外验证 |
| --- | --- |
| Schema、共享协议实现或生成器 | Web 完整协议向量、`npm --prefix web run verify:protocol`、Android 对应解析器测试和 Cloud 校验入口 |
| D1 migration、事务、查询或授权 | Cloud 全套，以及按联网指南运行本地 migration 和 Worker HTTP smoke |
| Room Schema、持久化队列 | Android 对应数据库/迁移测试，不能仅运行以上两类 |
| 共享业务/UI 或变体配置 | 相关离线/联网测试；提交阶段执行 CI 的构建、Lint 和变体矩阵 |
| Web 构建、依赖或资源边界 | `npm --prefix web run build:all` 和受影响模块测试 |
| 系统权限、通知、后台行为或设备适配 | 按模拟器测试指南选择受影响 API 的设备测试 |
| 发布 | 执行既有 CI 和发布指南中的完整门禁、签名及恢复验证 |

纯文档改动检查链接和 `git diff --check` 即可；只精简测试时运行被修改的套件并核对条数，不重跑无关的 Android 全矩阵。测试通过后不重复执行相同检查。
