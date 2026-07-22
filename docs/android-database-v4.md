# Android Room v4 规范

> 数据库：`dst-sub.db`
> Room 版本：4
> 应用版本：`0.1.0-alpha`
> 迁移：`1→2→3→4`

Room v4 完整继承 [v3 冻结规范](android-database-v3.md)，继续禁止破坏性迁移，并要求连续 migration、导出 Schema、最终 Schema 校验和数据保留测试。

## v4 变更

`task_instance` 新增可空字段 `groupNameSnapshot`，保存实例生成时的积分组名称。历史页面使用该快照，不使用可能已改名或归档的最新定义覆盖历史。

`MIGRATION_3_4` 执行：

1. 添加可空 `TEXT` 列；
2. 对仍有 `groupId` 关联的旧实例，从 `task_group.name` 回填；
3. 不修改实例状态、执行进度、积分、日志或结果修订。

新导入实例和重复任务生成实例时写入当前积分组名称。未分组实例保持 `NULL`。

导出 Schema：`app/schemas/com.ds.localtaskmanager.data.AppDatabase/4.json`。

## 查询边界

W23 的历史读取由 `HistoryRepository` 提供。UI 和 ViewModel 不直接访问 DAO；分页任务使用批量投影，禁止按实例循环读取备注、步骤或执行进度。
