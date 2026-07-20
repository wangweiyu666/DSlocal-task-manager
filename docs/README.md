# 文档索引

本目录按“产品规则、机器契约、实现约束、交付状态”分工。查找规则时先进入对应文档，避免在多个文件维护同一结论。

| 需要了解 | 权威文档 |
| --- | --- |
| 产品目标、业务规则、状态语义 | [需求总稿](preview.md) |
| DST1 字段与校验 | [JSON Schema](dst1-schema.json)、[测试向量](dst1-test-vectors.md) |
| Room 表、索引、迁移 | [数据库 v3](android-database-v3.md) |
| 当前进度、后续窗口 | [实施状态与路线图](android-phase-1-summary-and-roadmap.md) |
| 架构约束、交付流程 | [工程约束与交付清单](w00-w01-engineering-playbook.md) |
| 当前 Android 能力边界 | [Android 实现边界](android-phase-1.md) |
| 模拟器与仪器测试 | [Android 模拟器测试环境](android-emulator-testing.md) |

## 已完成窗口

- [W10：任务执行](w10-execution.md)
- [W11：重复任务](w11-recurrence.md)
- [W12：每日结果](w12-results.md)
- [W20：延期与重开](w20-delay-and-reopen.md)
- [W21：本地提醒与通知](w21-local-notifications.md)
- [W22：今日执行 UI](w22-today-execution-ui.md)

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
