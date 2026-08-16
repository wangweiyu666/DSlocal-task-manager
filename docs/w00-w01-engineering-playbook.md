# 工程约束与交付清单

> 来源：W00 协议契约与 W01 Room v1.3 冻结。适用于所有后续窗口。

## 不变量

1. **契约先于代码**：外部语义写入规范，数据结构写入数据库规范，代码和测试同步更新。
2. **合法性与能力分离**：合法但未实现返回 `CAPABILITY_NOT_IMPLEMENTED`；非法输入返回稳定协议错误。
3. **严格解析**：未知字段拒绝；日期、文本、枚举、集合和跨字段约束显式校验。
4. **错误可测试**：机器契约是 `code + path`，中文消息可调整。
5. **历史优先**：实例快照、流水、日志和结果修订不被最新定义或普通更新覆盖。
6. **职责分离**：定义、实例、执行进度、审计记录和派生结果各自拥有明确数据源。
7. **原子与幂等**：跨 DAO 操作使用一个事务；导入、生成、完成、迁移和重算必须可安全重试。
8. **UI 不复制规则**：Composable 和 ViewModel 只消费领域接口，不自行判断状态、积分或结果。

## 稳定分层

```text
protocol  外部数据合法性和强类型模型
domain    纯规划器、状态机和计算器
data      Room、Repository、Service、事务和审计
ui        状态展示与用户交互
platform  通知、文件、分享等 Android 适配
```

数据职责：

| 数据 | 事实来源 |
|---|---|
| 最新配置 | `task_definition` |
| 已发布任务 | `task_instance` 快照 |
| 步骤/计数/计时/正文/备注 | 对应进度表 |
| 实际积分 | `points_ledger` |
| 当前每日结果 | 实例与流水计算结果 |
| 结果历史 | `result_revision` |
| 行为审计 | `action_log` |

## 修改 DST1

必须同时检查：

- [`preview.md`](preview.md) 的字段和业务语义；
- [`dst1-schema.json`](dst1-schema.json)；
- [`dst1-test-vectors.md`](dst1-test-vectors.md)；
- `protocol-test-vectors/manifest.json` 与样例；
- `Dst1Decoder`、`Dst1Parser`、协议模型和错误码；
- Android 统一向量测试；
- 是否真的需要升级主版本。

测试至少覆盖正常值、边界值、越界值、类型正确但语义非法值，以及错误优先级。

## 修改 Room

必须同时检查：

- Entity、索引、唯一约束和外键；
- 负责该表的 DAO；
- `AppDatabase` 版本和连续 migration；
- Room 导出的 Schema JSON；
- 从每个受支持旧版本升级的数据保留测试；
- `PRAGMA foreign_key_check`；
- 备份兼容性和派生数据重建策略。

禁止使用 `fallbackToDestructiveMigration()` 掩盖迁移缺失。

## 修改状态、积分或结果

先明确：

```text
允许前态 → 后态
重复调用结果
失败回滚范围
action_log
points_ledger 冲正/迁移
result_revision
受影响日期和分组
```

当前结果可以重建；流水、日志和修订属于历史，不通过覆盖或删除“修正”。

## 标准工作流

### 开始前

1. 阅读 [`README.md`](README.md)、产品规范和当前窗口说明；
2. 检查 Git 状态与基线提交；
3. 明确交付、非目标和公共边界；
4. 先列状态矩阵、事务范围和幂等策略。

### 实施中

1. 先写纯模型或规划器测试；
2. 预览和正式执行复用同一规则；
3. Service 协调跨 DAO 行为；
4. 只加载受影响任务、日期和分组，避免 N+1；
5. 日志不写正文、备注或描述等敏感内容。

### 交付前

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
git diff --check
git status --short
```

确认：

- 新能力有正常、边界、重复和回滚测试；
- 旧测试继续通过；
- 文档没有保留已失效的临时规则；
- 没有提交 APK、报告或缓存；
- 没有顺手扩大到相邻窗口。

## 决策顺序

遇到新问题时依次判断：

1. 是否改变外部数据含义？是则先改协议契约和向量。
2. 是否改变保存结构或历史解释？是则先设计迁移和审计。
3. 是否跨多个领域？是则由 Service 在事务中协调。
4. 是否可能重放或中断？是则先定义幂等与恢复。
5. 是否只是能力尚未开放？是则保持协议合法性并返回能力状态。
6. 是否属于当前窗口？否则明确后置，不扩大范围。
