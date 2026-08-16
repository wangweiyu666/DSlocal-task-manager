# Android Room v1.6 规范

> 产品版本：Room v1.6
> 内部 Room Schema：`@Database(version = 6)`
> 迁移：内部 Schema `5→6`，禁止破坏性迁移

Room v1.6 为重复任务的单日例外增加持久化支持。产品文档中的旧称 Room v1–v5 统一写作 Room v1.1–v1.5；内部 Schema 数字、迁移类名和导出的 JSON 文件名仍保持 `1`–`6`，避免破坏数据库兼容性。

## 数据结构

新增 `recurrence_exception` 表，主键为 `(taskId, occurrenceDate)`：

- `cancelled`：该计划日是否撤销；
- `patchJson`：DST1.1 单日覆盖的规范 JSON；
- `createdAtEpochMillis` / `updatedAtEpochMillis`：用于审计和 DSTB1 合并；
- `taskId` 外键指向 `task_definition`，模板删除时级联清理。

`task_instance` 新增非空字段 `singleDayAdjusted`，默认 `0`。它是实例生成时的来源快照：今日页和当前任务详情显示“单日调整”，历史详情不增加标记。

## 生成与清理

- 例外按 `taskId + 计划日期` 查找，覆盖生成当日实例；撤销仍生成 `CANCELLED` 实例并消耗重复次数。
- 已生成实例保存最终有效快照，之后修改模板不会自动重算。
- 模板规则变化会删除已失效、尚未生成的例外并记录审计；已生成实例与历史不变。
- 重复任务转为临时任务时只删除尚未生成的例外。
- 全局撤销模板时未来例外休眠；恢复时保留仍有效的未来例外，清理不能回填的过去例外。

## 备份

DSTB1 容器版本保持 1；业务 JSON schema v2 新增 `recurrenceExceptions` 和实例字段 `singleDayAdjusted`。读取端继续接受业务 schema v1。
