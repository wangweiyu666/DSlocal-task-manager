# Android Room v3 冻结规范

> 数据库：`dst-sub.db`  
> Room 版本：3  
> 应用版本：`0.1.0-alpha`  
> 迁移：`1→2→3`

## 1. 兼容承诺

- 不再使用 `fallbackToDestructiveMigration()`；
- 每次版本升级必须提供连续 migration；
- migration 必须通过 Room 最终 Schema 校验和 `PRAGMA foreign_key_check`；
- Git 历史中的 v1/v2 数据字段与行必须保留；
- Schema JSON 由 Room 编译器生成并纳入版本控制。

Schema 位置：

- `app/schemas/com.ds.localtaskmanager.data.schema.AppDatabaseV1Schema/1.json`
- `app/schemas/com.ds.localtaskmanager.data.schema.AppDatabaseV2Schema/2.json`
- `app/schemas/com.ds.localtaskmanager.data.AppDatabase/3.json`

`AppDatabaseV1Schema` 与 `AppDatabaseV2Schema` 是只供编译器导出历史 Schema 的声明，不得在运行时实例化。

## 2. 表与所有权

| 表 | 用途 | DAO |
|---|---|---|
| `app_profile` | Dom 名称及应用资料 | `ProfileDao` |
| `import_batch` | 已导入批次与说明 | `ProfileDao` |
| `task_group` | 积分组定义与归档状态 | `DefinitionDao` |
| `task_definition` | 最新任务定义、重复/执行/提醒配置 | `DefinitionDao` |
| `task_step_definition` | 最新定义步骤 | `DefinitionDao` |
| `task_instance` | 实际执行实例及完整快照 | `InstanceDao` |
| `instance_step` | 实例步骤进度 | `InstanceDao` |
| `execution_progress` | 每实例计数或累计计时进度 | `ExecutionDao` |
| `information_submission` | 每实例一条告知正文 | `ExecutionDao` |
| `task_note` | 每实例一条普通备注 | `ExecutionDao` |
| `points_ledger` | 正向积分与冲正流水 | `AuditDao` |
| `action_log` | 不可变操作日志 | `AuditDao` |
| `result_revision` | 每日结果修订记录 | `ResultDao` |
| `reminder_record` | 已发布实例的实际通知计划 | `ReminderDao` |

## 3. 任务配置与实例快照

`task_definition` 保存最新配置，`task_instance` 保存实例发布时快照。

重复配置字段：

- `recurrenceFrequency`
- `recurrenceStartDate`
- `recurrenceEndDate`
- `recurrenceCount`
- `recurrenceWeekdaysMask`
- `recurrenceDeadlineTime`

执行配置字段：

- `executionKind`：`NORMAL / COUNTER / TIMER / INFORMATION`
- `executionAction`
- `executionTarget`

提醒分钟数组使用唯一、降序的规范 JSON，存入 `reminderMinutesJson`。实例额外保存：

- `category`：`DAILY / WEEKLY / TEMPORARY`
- `publishedAtEpochMillis`

v1/v2 数据迁移后的默认值为 `executionKind=NORMAL`、`category=TEMPORARY`，发布时间使用原实例创建时间。

W11 将缺失的重复开始日期解析为首次导入任务日，并把有效日期保存到 `recurrenceStartDate`。星期使用 bit 0～6 对应周一～周日；缺失截止时间规范化为 `04:00`，显式 `null` 继续保存为 `null`。日期实例使用 ISO 计划日期作为 `occurrenceKey`，分类为 `DAILY` 或 `WEEKLY`。

## 4. 新表结构

### `execution_progress`

每个实例最多一行。`COUNTER` 使用 `counterValue`，`TIMER` 使用 `elapsedMillis`。运行中的单调时钟起点不持久化。

W10 起计数值限制在 `0..executionTarget`；计时按毫秒累计并在目标秒数处截断。离开执行页、应用进入后台或锁屏时，由前台计时控制器结算本段单调时钟时间。重启后保留累计值但不自动继续。

### `information_submission`

每个实例最多一条当前正文，包含创建、更新和提交时间。正文版本历史不重复保存；编辑事件进入 `action_log`。

正文去除首尾空白后必须非空，最多 2000 个 Unicode 码点。保存草稿会清除当前提交时间；完成任务时在同一事务内设置提交时间。审计日志只记录长度，不复制正文。

### `task_note`

每个实例最多一条可编辑普通备注。清空由上层服务删除该行；编辑事件进入 `action_log`。

### `result_revision`

使用结构化旧/新状态与积分列。`scope` 为 `GLOBAL` 或 `GROUP`；相关任务 ID 存为排序、去重的规范 JSON 数组。

### `reminder_record`

主键为 `(taskId, occurrenceKey, minutesBeforeDeadline)`，状态为 `SCHEDULED / DELIVERED / CANCELLED / SKIPPED`。该表不进入备份，恢复后重新生成。

## 5. 外键与删除策略

- 定义步骤随任务定义级联删除；
- 实例步骤、执行进度、告知正文、普通备注和提醒记录随实例级联删除；
- 任务或实例的积分组被物理删除时使用 `SET NULL`，但正常产品流程只允许归档；
- 积分流水、操作日志和结果修订使用 `NO ACTION`，禁止级联抹除审计历史；
- 任务定义与实例正常流程不物理删除，通过撤销和状态字段表达。

## 6. 稳定代码边界

- `TaskRepository` + `RoomTaskRepository`
- `ImportService` + `RoomImportService`
- `TaskExecutionService` + `RoomTaskExecutionService`

UI 与 ViewModel 只依赖接口。Room 实现在 `DstApplication` 完成装配；跨 DAO 的原子操作仍由 `AppDatabase.withTransaction` 管理。

UI 功能目录：

- `ui/navigation/`
- `ui/today/`
- `ui/history/`
- `ui/profile/`

顶层 `ui/DstApp.kt` 仅保留兼容入口。
