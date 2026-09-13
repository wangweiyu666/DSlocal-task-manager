# 最小测试与交付流程

2026-09-12 起按用户要求减少执行次数和数量：同一源码只做一次受影响端的最小验证，提交、推送、部署和打包共用结果。旧回归保留为正式环境截图或用户手动验收发现问题后的诊断入口；不因发布、版本号、依赖或改动路径自动运行全套。

## 默认集合

| 改动 | 默认检查 |
| --- | --- |
| Android | production 活动单元集合 33 个静态方法；两变体构建/Lint、offline 网络边界；production 截图 |
| Web / Cloud | Web `test:minimum` 7 条、Cloud `test:minimum` 2 条；各端类型检查、协议生成一致性、双 Web 构建与边界检查；正式构建 CSP 启动 smoke |
| 纯文档 | 引用和 `git diff --check`，不启动产品测试 |
| CI / 发布脚本 | 相关脚本逻辑、语法及选择规则检查，不因此运行完整产品套件 |
| 部署 / 打包 | 复用同 SHA 通过的检查；产物、签名、环境和 SHA-256 审计 |

Web、Cloud 的 `npm test` 仍是完整诊断入口；默认使用 `npm --prefix web run test:minimum` / `npm --prefix cloud run test:minimum`。Android 清单与命令见下节。静态数量不是运行通过证据。

近期新增或刚修复的行为留在活动集合。旧集合已移为按需执行，代码和断言保留。后续新用例经过至少三个发布版本且对应正式截图/手动验收无相关问题后，可从活动集合移到旧回归，并记录版本及证据；这不要求每版重跑旧用例。现有历史仅有部分版本级汇总，不能声称已逐条证明稳定。

## Android 测试集合

| 场景 | 命令 | 静态方法数 |
| --- | --- | --- |
| 默认活动回归 | `.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release --no-daemon` | 33 |
| 日常兼容入口 | 同上，属性改为 `daily`；集合相同，不重复执行 | 33 |
| 旧发布回归，按故障诊断需要 | 属性改为 `regression` | 173 |
| 全部独立单元测试，按故障诊断需要 | 属性改为 `full`，或不传 profile | 200 |
| 已验证源码打包 | `.\gradlew.bat assembleOfflineDebug assembleProductionDebug --no-daemon` | 不运行单元测试 |

原有四类：`ConditionalChoiceTest`、`AppDatabaseMigrationTest`、`InformationAutoSaveTest`、`ChoiceAutoSaveTest`，保留近期条件分支、积分、迁移和自动保存的回归。新增 `TodayStartupTest` 和 `ConnectedSyncEngineTest.startup*` 五项启动与恢复回归（含原有启动恢复用例），支持按方法通配符选择。旧清单完整保存于 `scripts/testing/android-regression.txt`，没有删除用例或削弱断言。

`python scripts/testing/inspect-android-profiles.py` 校验清单并报告静态方法数；这些不是运行通过数。实际运行数量和结果以 JUnit XML 为准。

指定类使用不带 profile 的 `--tests 完整类名`，避免与 profile 取交集漏测。`full_android_matrix` 诊断模式额外运行 offline 单元和截图。发布准备默认 `-TestProfile reuse`；诊断时可用 `-TestProfile release|full -DiagnosticReason <问题>`。历史逐项结论见 [测试审查](android-test-audit-2026-09-09.md)。

## 一次提交，一次验证

1. 开发时只测当前修改直接涉及的场景。最终源码不再本地跑一遍发布套件再让 CI 重跑；优先由推送后的适用 CI 提供交付证据。
2. 显式暂存本次文件，DCO 提交一次、正常推送一次，固定完整 SHA。后续部署、两份 APK 和标签全部使用该 SHA；已有运行先检查，不重复 dispatch。
3. Android CI 按路径触发，网页/CSS 修改不跑 Android。Cloud Connected 统一负责 Web/Cloud 验证和构建，上传带 SHA 的 `cloud-verification-<SHA>`。
4. Dom Web Pages 等 Cloud 验证成功后复用 offline Web 构建，不再安装依赖、测试或重建。仅可信 main push 自动触发；手动入口也要求现成验证产物。
5. 正式部署先寻找同 SHA、可信 main 运行中成功的 verify 作业及未过期产物；找到则跳过 verify 并部署原构建。缺失或过期时只做一次最小验证。部署失败后的重试可复用此前成功的 verify，不复用失败、取消或不同 SHA 的检查；最新验证失败时禁止回退到更旧成功记录。
6. Android 发布准备默认读取同 SHA 成功 CI，再分别构建和审计两份签名 APK；不重复运行单元和截图。发布授权、受保护环境、签名、版本、证据和校验值检查保持有效。纯网页部署无需发布 Android 新版本。

如果尚无同源证据，不能把省略检查写成通过。记录实际命令、源码状态、通过/失败/跳过数和未覆盖范围。任何活动检查失败仍需修复，不允许靠其他测试通过覆盖失败。

## 发现问题后才扩大

正式环境截图或用户手动验收出现问题时，记录症状、版本/提交和复现步骤，先运行对应旧测试类；涉及多个模块或无法定位时再运行完整诊断。Cloud CI 的 `full_regression`、Android CI 的 `full_unit_suite` / `full_android_matrix` 均要求 `diagnostic_reason`。完整 Cloud 诊断另含 Access、协议、迁移和本地 HTTP smoke。

修复后只重跑失败场景和改动直接影响的活动检查，不重复已通过的无关端套件；新增回归进入活动集合。同步重复回执、事务回滚、保存竞态、权限、备份和迁移等旧断言继续保留用于诊断，不互相替代或降低断言。

## 正式截图与手动验收

正式管理网页使用现有授权登录会话，按本次变化检查页面、移动布局和控制台；不为验收修改真实业务数据。生产构建的模拟 CSP smoke、Android production 变体渲染均不能代替线上登录或真实设备证据。步骤见 [生产运行手册](stage4-production-runbook.md#管理员页面加载验收)。

完整 Android 测试成功后仍交付已测试变体的同源 Debug APK；完整双变体矩阵后交付 offline 和 production。复用产物，不为交付再测一次。定向/工作流测试不要求重新构建 APK；公开发布、部署与设备安装遵循用户当次授权。
