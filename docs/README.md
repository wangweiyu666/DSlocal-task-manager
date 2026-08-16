# 文档索引

本目录按“产品规则、机器契约、实现约束、交付状态”分工。查找规则时先进入对应文档，避免在多个文件维护同一结论。

| 需要了解 | 权威文档 |
| --- | --- |
| 产品目标、业务规则、状态语义 | [需求总稿](preview.md) |
| DST1 字段与校验 | [JSON Schema](dst1-schema.json)、[测试向量](dst1-test-vectors.md) |
| Room 表、索引、迁移 | [数据库 v4 基础规范](android-database-v4.md)、[W25 Room v5 统计索引](w25-profile-statistics.md#数据库与数据边界) |
| 当前基线与归档状态 | [实施状态与路线图](android-phase-1-summary-and-roadmap.md)、[W00–W32 归档摘要](archive-summary.md) |
| 联网 Android、联网 Web 与 Cloudflare 任务流程 | [联网版本实施路线图](cloud-connected-roadmap.md) |
| 架构约束、交付流程 | [工程约束与交付清单](w00-w01-engineering-playbook.md) |
| 当前 Android 能力边界 | [Android 实现边界](android-phase-1.md) |
| Android UI 层级与返回导航 | [Android UI 与返回导航](android-ui-navigation.md) |
| DSTB1 文件格式与兼容 | [DSTB1 格式](dstb1-format.md)、[测试向量](dstb1-test-vectors.md) |
| 模拟器与仪器测试 | [Android 模拟器测试环境](android-emulator-testing.md) |

## 已完成窗口

- [W00–W01：协议、数据库与工程基线](w00-w01-engineering-playbook.md)
- [W10：任务执行](w10-execution.md)
- [W11：重复任务](w11-recurrence.md)
- [W12：每日结果](w12-results.md)
- [W20：延期与重开](w20-delay-and-reopen.md)
- [W21：本地提醒与通知](w21-local-notifications.md)
- [W22：今日执行 UI](w22-today-execution-ui.md)
- [W23：历史页面](w23-history-ui.md)
- [W24：今日结果与分享](w24-results-and-sharing.md)
- [W25：“我的”统计与积分流水](w25-profile-statistics.md)
- [W26：独立设置页面](w26-settings.md)
- [W31：DSTB1 备份与恢复](w31-backup-and-restore.md)
- [W32：发布加固](w32-release-hardening.md)
- [W33：UI 配色选择](w33-ui-palettes.md)

## 当前归档状态

Android W00–W32 已完成并归档，历史基线见 [W00–W32 归档摘要](archive-summary.md)。当前公开基线为 `0.1.0-alpha.3`（versionCode 4），包含 W33 UI 配色选择和分享图片体验优化。

联网版本尚未实施。已确认的产品边界、四阶段依赖顺序、Cloudflare 免费层门禁和 `rochelimit.me` 域名审核前置条件见[联网版本实施路线图](cloud-connected-roadmap.md)。

## 配套资产

- [`samples/`](../samples/README.md)：人工验收用 DST1 样例；
- [`protocol-test-vectors/`](../protocol-test-vectors/README.md)：解析器共享协议向量；
- [`app/schemas/`](../app/schemas/)：Room 导出 Schema；
- [`app/src/test/`](../app/src/test/) 与 [`app/src/androidTest/`](../app/src/androidTest/)：JVM 和仪器测试。

窗口文档记录实现决策和验收证据，不重复定义产品规则。发生冲突时按以下顺序处理：

1. 产品语义以需求总稿为准；
2. 线格式以 Schema 和测试向量为准；
3. 持久化结构以数据库文档和导出的 Room Schema 为准；
4. 代码现状与路线图不一致时，先核实测试和实现，再更新路线图。

## 变更规则

| 变更类型 | 必须同步更新 |
| --- | --- |
| 业务语义 | 需求总稿、对应测试 |
| DST1 格式 | Schema、测试向量、解析器测试 |
| Room 结构 | 数据库文档、migration、导出 Schema、迁移测试 |
| 窗口完成 | 路线图、对应窗口说明 |
