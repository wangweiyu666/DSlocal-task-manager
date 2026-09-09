# Android 用例必要性审查（2026-09-09）

范围：当前源码中每个 `@Test` 与 `@PreviewTest` 入口。按测试方法、断言和关键集成边界审查其必要性；这不是所有测试在本轮均已运行的声明，也不是所有断言已充分的声明。协议向量内部的多组输入全部保留。

结论：仅合并两个相同规则的边界方法组，未删除业务场景、迁移版本或协议向量。新增前后台轮询回归后，日常 37、发布核心 148、完整 174 个单元方法。保留项中的非核心展示、统计与性能覆盖移至相关改动触发，完整入口仍包括它们。每条入口的具体处置见下表。

后续新增审查：`ConnectedSyncEngineTest.notification polling stops in background resumes once and leaves worker usable` 保留；三轮可见／后台切换验证 15 秒边界、后台不请求、恢复无重复循环及后台 Worker 入口仍可用。它补充生命周期取消覆盖，不替代真机厂商调度验证。连同原 231 个入口，现共 232 个。

## 已落实的合并

- `time before four belongs to previous task day` 与 `four o'clock starts a new task day` → `task day changes exactly at four`，两个时间边界保留。
- `whole-hour deadline retains zero minutes` 与 `deadline is consistently displayed to minutes` → 后者的数据表，三个输入保留。

## 断言局限与后续补强位置

- `skyPaletteChangesShareImageAccent` 断言颜色常量并执行渲染，没有比较输出像素；保留为基础 smoke，不能据此宣称图片颜色验证通过。
- `counter writes are serialized and latest value is retained` 使用连续写入，没有阻塞首个计数写入；保留基础保存用例，不能替代计数写入并发场景。现有阻塞信息写入和心情竞态用例不删除。
- `readOnlyStepsHaveNoEditingActionsAndConfirmedAnswersCanBeUndone` 实际断言只读状态禁止撤销；不能据名称宣称可编辑状态撤销已被 UI 验证。
- `emptyResultHasNoShareActions` 目前只断言空状态文字；保留空状态 smoke，不能据此宣称已断言所有分享动作不存在。
- `SyncTraceTest` 默认关闭日志时的非法明文分支不能证明启用后的脱敏；诊断构建的两条测试已在前轮以 `-PsyncDiagnostics=true` 通过，不因默认集合通过而替代该证据。
- WorkManager 配置单元测试不证明厂商系统后台启动能力。2026-09-09 真机发现 MIUI AutoStartManager 拒绝 SystemJobService；临时开启自启动后独立后台补传通过，关闭时不通过。见[对照证据](android-background-sync-verification-2026-09-09.md)。

这些局限不会通过删除测试、降低断言或新增 skip 掩盖。本轮未修改上述业务实现；后续相关功能修改时应在原用例补强，而非再叠加内容重复的用例。

## 逐项处置

入口统计：test=143, testConnected=30, androidTest=33, screenshotTest=25。计数以方法为单位，运行数量以报告为准。


| 入口（源码链接） | 处置与理由 |
| --- | --- |
| [newerBackupWinsAndLocalSettingsStay](../app/src/test/java/com/ds/localtaskmanager/backup/BackupMergerTest.kt#L8) | 保留；版本选择、同版本冲突选择和不可变积分碰撞属于不同恢复风险。 |
| [equalTimestampConflictDefaultsLocalAndCanChooseBackup](../app/src/test/java/com/ds/localtaskmanager/backup/BackupMergerTest.kt#L26) | 保留；版本选择、同版本冲突选择和不可变积分碰撞属于不同恢复风险。 |
| [immutableIdCollisionIsRejected](../app/src/test/java/com/ds/localtaskmanager/backup/BackupMergerTest.kt#L39) | 保留；版本选择、同版本冲突选择和不可变积分碰撞属于不同恢复风险。 |
| [roundTripIsStable](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L10) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [changedByteFailsWithoutReturningPayload](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L29) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [truncatedFileFails](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L40) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [unknownFutureVersionRequestsUpgrade](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L46) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [duplicateKeysAreRejected](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L57) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [business schema 2 preserves recurrence exceptions and schema 1 remains readable](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L66) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [business schema 3 round trips mood submissions](../app/src/test/java/com/ds/localtaskmanager/backup/DstbCodecTest.kt#L97) | 保留；文件完整性、格式版本、业务版本兼容和键唯一性分别保留。 |
| [replace and snapshot preserve a valid completed mood parent and answer](../app/src/test/java/com/ds/localtaskmanager/backup/MoodBackupRoomRegressionTest.kt#L31) | 保留；校验心情父实例与提交快照一致，不能由通用编解码代替。 |
| [merge keeps completed mood snapshot over newer draft and excludes mood for normal parent](../app/src/test/java/com/ds/localtaskmanager/backup/MoodBackupRoomRegressionTest.kt#L42) | 保留；校验心情父实例与提交快照一致，不能由通用编解码代替。 |
| [snapshotUsesStableBusinessTables](../app/src/test/java/com/ds/localtaskmanager/backup/RoomBackupRepositoryTest.kt#L36) | 保留；真实 Room 快照和失败回滚验证落库边界。 |
| [failedReplacementRollsBackExistingData](../app/src/test/java/com/ds/localtaskmanager/backup/RoomBackupRepositoryTest.kt#L46) | 保留；真实 Room 快照和失败回滚验证落库边界。 |
| [payload codec and Room replace preserve mixed step snapshots across instances](../app/src/test/java/com/ds/localtaskmanager/backup/StepsBackupRegressionTest.kt#L35) | 保留；步骤组整体版本选择、旧 ID 转换与多实例恢复互不替代。 |
| [v3 missing step ids restore deterministically for every occurrence and preserve completion](../app/src/test/java/com/ds/localtaskmanager/backup/StepsBackupRegressionTest.kt#L62) | 保留；步骤组整体版本选择、旧 ID 转换与多实例恢复互不替代。 |
| [merge chooses newer instance step group and exposes definition step source conflict](../app/src/test/java/com/ds/localtaskmanager/backup/StepsBackupRegressionTest.kt#L88) | 保留；步骤组整体版本选择、旧 ID 转换与多实例恢复互不替代。 |
| [validator rejects invalid step matrices but accepts legacy normal steps](../app/src/test/java/com/ds/localtaskmanager/backup/StepsBackupRegressionTest.kt#L114) | 保留；步骤组整体版本选择、旧 ID 转换与多实例恢复互不替代。 |
| [version 3 migrates to current and preserves execution data](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L35) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 5 opens without destructive recreation](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L58) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 6 migration adds mood submissions without changing existing rows](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L85) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 7 steps migrate to stable step id primary key](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L118) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 1 migrates through version 2 and preserves task data](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L158) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 2 migrates audit rows and creates the frozen tables](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L184) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [version 4 migration creates statistics indexes without changing data](../app/src/test/java/com/ds/localtaskmanager/data/AppDatabaseMigrationTest.kt#L236) | 保留；不同起始版本的数据形状和迁移入口不同，不合并为只测最新版本。 |
| [history pages by task date and searches snapshot group and note](../app/src/test/java/com/ds/localtaskmanager/data/history/W23HistoryRepositoryTest.kt#L40) | 保留；搜索、组合筛选与大数据分页分别保留，历史代码变更时运行。 |
| [status source requirement and selected date filters compose](../app/src/test/java/com/ds/localtaskmanager/data/history/W23HistoryRepositoryTest.kt#L65) | 保留；搜索、组合筛选与大数据分页分别保留，历史代码变更时运行。 |
| [ten thousand instances still return only one thirty-day page](../app/src/test/java/com/ds/localtaskmanager/data/history/W23HistoryRepositoryTest.kt#L83) | 保留；搜索、组合筛选与大数据分页分别保留，历史代码变更时运行。 |
| [preview and atomic import create definition instance group steps and batch](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L48) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [same batch cannot be imported twice](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L66) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [required steps gate completion and undo writes compensating ledger](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L76) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [moving task migrates old points with compensating ledger](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L95) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [dom cancellation keeps a completed instance completed and scored](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L114) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [transaction rolls back every write when a later log insert fails](../app/src/test/java/com/ds/localtaskmanager/data/ImportAndExecutionServiceTest.kt#L128) | 保留；导入批次幂等、提交事务、积分变动和失败回滚必须保留。 |
| [daily mood answers stay isolated and type changes clear only pending occurrence](../app/src/test/java/com/ds/localtaskmanager/data/MoodInstanceIsolationTest.kt#L48) | 保留；真实每日实例与执行类型变化的隔离验证。 |
| [ten thousand instances and one hundred thousand ledger rows meet W25 load targets](../app/src/test/java/com/ds/localtaskmanager/data/statistics/W25StatisticsPerformanceTest.kt#L40) | 保留；保留负载和耗时阈值，移出日常及发布核心，统计改动时运行。 |
| [dashboard uses task day, excludes futures, and counts final instance statuses](../app/src/test/java/com/ds/localtaskmanager/data/statistics/W25StatisticsRepositoryTest.kt#L44) | 保留；统计值、积分转移与分页边界保留为按需覆盖。 |
| [ledger merges transfer and preserves both sides when drilling into either group](../app/src/test/java/com/ds/localtaskmanager/data/statistics/W25StatisticsRepositoryTest.kt#L81) | 保留；统计值、积分转移与分页边界保留为按需覆盖。 |
| [task day rolls over at four in the morning](../app/src/test/java/com/ds/localtaskmanager/data/statistics/W25StatisticsRepositoryTest.kt#L103) | 保留；统计值、积分转移与分页边界保留为按需覆盖。 |
| [ledger pagination does not split a transfer or duplicate it on the next page](../app/src/test/java/com/ds/localtaskmanager/data/statistics/W25StatisticsRepositoryTest.kt#L117) | 保留；统计值、积分转移与分页边界保留为按需覆盖。 |
| [mixed steps enforce order target and final optional skip with one ledger](../app/src/test/java/com/ds/localtaskmanager/data/StepsExecutionRegressionTest.kt#L50) | 保留；真实事务验证顺序、跳过、撤销、目标和一次积分。 |
| [undo earlier step resets later confirmations but preserves answers and overall undo preserves result](../app/src/test/java/com/ds/localtaskmanager/data/StepsExecutionRegressionTest.kt#L77) | 保留；真实事务验证顺序、跳过、撤销、目标和一次积分。 |
| [malformed confirmed answer cannot bypass target and occurrences stay isolated](../app/src/test/java/com/ds/localtaskmanager/data/StepsExecutionRegressionTest.kt#L106) | 保留；真实事务验证顺序、跳过、撤销、目标和一次积分。 |
| [import keeps stable ids and resets confirmation from first reordered step](../app/src/test/java/com/ds/localtaskmanager/data/StepsImportRegressionTest.kt#L44) | 保留；稳定身份、重排、配置变化与例外的导入进度处理互不等价。 |
| [renaming a step preserves confirmed answer while changing its leaf target resets it](../app/src/test/java/com/ds/localtaskmanager/data/StepsImportRegressionTest.kt#L56) | 保留；稳定身份、重排、配置变化与例外的导入进度处理互不等价。 |
| [legacy counter progress moves into supplied deterministic root step](../app/src/test/java/com/ds/localtaskmanager/data/StepsImportRegressionTest.kt#L74) | 保留；稳定身份、重排、配置变化与例外的导入进度处理互不等价。 |
| [exception only import uses stored recurring base and updates that day](../app/src/test/java/com/ds/localtaskmanager/data/StepsImportRegressionTest.kt#L89) | 保留；稳定身份、重排、配置变化与例外的导入进度处理互不等价。 |
| [exception only import rejects empty steps and requires explicit clear when leaving steps](../app/src/test/java/com/ds/localtaskmanager/data/StepsImportRegressionTest.kt#L101) | 保留；稳定身份、重排、配置变化与例外的导入进度处理互不等价。 |
| [import stores counter definition and instance snapshot](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L55) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [counter target gates explicit completion and undo preserves progress](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L68) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [successful completion and undo notify after committed database state](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L87) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [failed completion and draft save do not notify](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L111) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [counter rejects values outside the imported target](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L127) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [counter configuration update resets mutable progress and archives summary](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L140) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [finished instance keeps its original execution snapshot on definition update](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L154) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [timer controller accumulates monotonic foreground segments and clamps target](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L168) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [information draft uses code points locks on completion and survives undo](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L189) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [information requirement update preserves draft and appears in preview](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L208) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [information enforces empty and 2000 code point limits](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L225) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [mood requires a rating and preserves nullable text through completion and undo](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L238) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [mood accepts ratings one through five and rejects out of range](../app/src/test/java/com/ds/localtaskmanager/data/W10ExecutionServiceTest.kt#L261) | 保留；执行方式、输入范围、提交回调和进度快照各有独立故障面。 |
| [first daily import starts today and never creates once instance](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L56) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [offline reconciliation backfills missed dates and remains idempotent](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L72) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [template update preserves old snapshot and new date uses new snapshot](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L90) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [DST 1_1 applies current override and materializes future cancellation](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L102) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [clear exception rebases mutable instance onto current template](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L122) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [generated count limits actual occurrences](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L135) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [cancellation stops generation and restore skips cancelled interval](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L145) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [W10 execution service operates on dated occurrence key](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L160) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [single and recurring template conversion cancels competing active instances](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L173) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [generation transaction rolls back all dates when second audit insert fails](../app/src/test/java/com/ds/localtaskmanager/data/W11RecurrenceServiceTest.kt#L186) | 保留；补实例、模板修订、例外和生成回滚需保留真实 Room 验证。 |
| [completion and undo recalculate current result and append revisions](../app/src/test/java/com/ds/localtaskmanager/data/W12ResultServiceTest.kt#L52) | 保留；积分账本与当前结果、历史修订同步更新，不能由计算器测试代替。 |
| [moving a scored task transfers historical points and keeps global total](../app/src/test/java/com/ds/localtaskmanager/data/W12ResultServiceTest.kt#L70) | 保留；积分账本与当前结果、历史修订同步更新，不能由计算器测试代替。 |
| [required flag update recalculates historical result without changing earned points](../app/src/test/java/com/ds/localtaskmanager/data/W12ResultServiceTest.kt#L91) | 保留；积分账本与当前结果、历史修订同步更新，不能由计算器测试代替。 |
| [cancellation removes a completed task result while retaining ledger](../app/src/test/java/com/ds/localtaskmanager/data/W12ResultServiceTest.kt#L106) | 保留；积分账本与当前结果、历史修订同步更新，不能由计算器测试代替。 |
| [foreground reconciliation marks overdue task missed and revises result](../app/src/test/java/com/ds/localtaskmanager/data/W12ResultServiceTest.kt#L119) | 保留；积分账本与当前结果、历史修订同步更新，不能由计算器测试代替。 |
| [omitted y preserves date and future deadline reopens missed task with progress](../app/src/test/java/com/ds/localtaskmanager/data/W20DelayServiceTest.kt#L55) | 保留；延期、日期移动与旧实例兼容属于业务数据迁移边界。 |
| [explicit y moves completed task date and its earned points](../app/src/test/java/com/ds/localtaskmanager/data/W20DelayServiceTest.kt#L86) | 保留；延期、日期移动与旧实例兼容属于业务数据迁移边界。 |
| [deadline-derived date never migrates an existing task without explicit y](../app/src/test/java/com/ds/localtaskmanager/data/W20DelayServiceTest.kt#L108) | 保留；延期、日期移动与旧实例兼容属于业务数据迁移边界。 |
| [delayed recurring occurrence is a new independent temporary task](../app/src/test/java/com/ds/localtaskmanager/data/W20DelayServiceTest.kt#L119) | 保留；延期、日期移动与旧实例兼容属于业务数据迁移边界。 |
| [import persists existing h in definition and instance snapshots](../app/src/test/java/com/ds/localtaskmanager/data/W21ReminderServiceTest.kt#L49) | 保留；提醒登记幂等和权限拒绝不改变任务状态。 |
| [reconcile is idempotent and delivery records one private notification](../app/src/test/java/com/ds/localtaskmanager/data/W21ReminderServiceTest.kt#L59) | 保留；提醒登记幂等和权限拒绝不改变任务状态。 |
| [permission denial skips without changing task state](../app/src/test/java/com/ds/localtaskmanager/data/W21ReminderServiceTest.kt#L75) | 保留；提醒登记幂等和权限拒绝不改变任务状态。 |
| [event buffer is bounded and report excludes user content](../app/src/test/java/com/ds/localtaskmanager/diagnostics/W32DiagnosticServiceTest.kt#L29) | 保留；诊断容量上限与私有内容排除。 |
| [required status has strict precedence and optional tasks do not lower it](../app/src/test/java/com/ds/localtaskmanager/domain/DailyResultCalculatorTest.kt#L13) | 保留；纯计算规则覆盖状态优先级、空结果和实际账本积分。 |
| [optional only and no result states are distinct](../app/src/test/java/com/ds/localtaskmanager/domain/DailyResultCalculatorTest.kt#L30) | 保留；纯计算规则覆盖状态优先级、空结果和实际账本积分。 |
| [calculator aggregates actual ledger points and group messages](../app/src/test/java/com/ds/localtaskmanager/domain/DailyResultCalculatorTest.kt#L37) | 保留；纯计算规则覆盖状态优先级、空结果和实际账本积分。 |
| [missing y preserves old date while later deadline reopens missed task](../app/src/test/java/com/ds/localtaskmanager/domain/InstanceUpdatePlannerTest.kt#L17) | 保留；纯规划函数边界保留；Room 延期测试补充事务集成，两层不互相替代。 |
| [explicit different y is required for date migration](../app/src/test/java/com/ds/localtaskmanager/domain/InstanceUpdatePlannerTest.kt#L28) | 保留；纯规划函数边界保留；Room 延期测试补充事务集成，两层不互相替代。 |
| [later deadline still in past does not reopen and completed never reopens](../app/src/test/java/com/ds/localtaskmanager/domain/InstanceUpdatePlannerTest.kt#L48) | 保留；纯规划函数边界保留；Room 延期测试补充事务集成，两层不互相替代。 |
| [missing deadline defaults from preserved date and infinite deadline is an extension](../app/src/test/java/com/ds/localtaskmanager/domain/InstanceUpdatePlannerTest.kt#L63) | 保留；纯规划函数边界保留；Room 延期测试补充事务集成，两层不互相替代。 |
| [daily plan honors start end existing dates and count](../app/src/test/java/com/ds/localtaskmanager/domain/RecurrencePlannerTest.kt#L18) | 保留；日期选择、数量与截止规则的快速纯函数覆盖。 |
| [weekly plan selects ordered weekdays](../app/src/test/java/com/ds/localtaskmanager/domain/RecurrencePlannerTest.kt#L42) | 保留；日期选择、数量与截止规则的快速纯函数覆盖。 |
| [weekday mask and task-day deadlines are stable](../app/src/test/java/com/ds/localtaskmanager/domain/RecurrencePlannerTest.kt#L65) | 保留；日期选择、数量与截止规则的快速纯函数覆盖。 |
| [plans only future reminders published with the instance](../app/src/test/java/com/ds/localtaskmanager/domain/ReminderPlannerTest.kt#L12) | 保留；未来提醒、终态取消和当前时刻不补发是不同边界。 |
| [terminal instance cancels every reminder](../app/src/test/java/com/ds/localtaskmanager/domain/ReminderPlannerTest.kt#L33) | 保留；未来提醒、终态取消和当前时刻不补发是不同边界。 |
| [reminder at current instant is not backfilled](../app/src/test/java/com/ds/localtaskmanager/domain/ReminderPlannerTest.kt#L47) | 保留；未来提醒、终态取消和当前时刻不补发是不同边界。 |
| [task day changes exactly at four](../app/src/test/java/com/ds/localtaskmanager/domain/TaskDayTest.kt#L9) | 保留；已合并原两个方法；03:59 与 04:00 两个输入仍独立断言。 |
| [sharedCloudManifestReferencesReadableJsonVectors](../app/src/test/java/com/ds/localtaskmanager/protocol/CloudProtocolVectorsTest.kt#L15) | 保留；共享资源完整性与跨端 occurrence key 运算分别保留。 |
| [occurrenceKeyMatchesSharedVector](../app/src/test/java/com/ds/localtaskmanager/protocol/CloudProtocolVectorsTest.kt#L31) | 保留；共享资源完整性与跨端 occurrence key 运算分别保留。 |
| [valid envelope is verified and inflated](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1DecoderTest.kt#L13) | 保留；轻量解码单元边界保留；向量测试补充完整协议集成。 |
| [checksum mismatch is rejected](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1DecoderTest.kt#L20) | 保留；轻量解码单元边界保留；向量测试补充完整协议集成。 |
| [execution objects become typed DST1 models](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L33) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [recurrence objects become typed DST1 models](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L44) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [existing reminder field becomes typed minutes](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L72) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [DST 1_1 occurrence exceptions preserve missing null empty and cancellation](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L80) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [DST 1_1 cancellation cannot carry overrides](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L94) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [task model preserves whether y and l were explicitly supplied](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L106) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [shared manifest drives every protocol vector](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L123) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [manifest covers every committed fixture](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L150) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [schema is draft 2020-12 and closes every protocol object](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L167) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [committed manual samples decode and parse](../app/src/test/java/com/ds/localtaskmanager/protocol/Dst1ParserTest.kt#L183) | 保留；类型映射、字段缺省语义、协议向量和样例兼容分别保留。 |
| [seeded random bytes never escape as virtual machine failure](../app/src/test/java/com/ds/localtaskmanager/protocol/W32MaliciousInputTest.kt#L12) | 保留；随机坏输入与深层/超大输入拒绝保护不同资源边界。 |
| [deep and oversized json is rejected without stack overflow](../app/src/test/java/com/ds/localtaskmanager/protocol/W32MaliciousInputTest.kt#L22) | 保留；随机坏输入与深层/超大输入拒绝保护不同资源边界。 |
| [missingValuesUseSafeDefaults](../app/src/test/java/com/ds/localtaskmanager/settings/W26AppSettingsRepositoryTest.kt#L28) | 保留；默认值、持久化、迁移和坏配置恢复保留为设置改动补测。 |
| [settingsPersistAcrossRepositoryInstances](../app/src/test/java/com/ds/localtaskmanager/settings/W26AppSettingsRepositoryTest.kt#L40) | 保留；默认值、持久化、迁移和坏配置恢复保留为设置改动补测。 |
| [legacyNotificationAndShareFlagsAreMigrated](../app/src/test/java/com/ds/localtaskmanager/settings/W26AppSettingsRepositoryTest.kt#L60) | 保留；默认值、持久化、迁移和坏配置恢复保留为设置改动补测。 |
| [invalidEnumValuesFallBackAndPrivacyCanBeReset](../app/src/test/java/com/ds/localtaskmanager/settings/W26AppSettingsRepositoryTest.kt#L72) | 保留；默认值、持久化、迁移和坏配置恢复保留为设置改动补测。 |
| [resultAndInformationImagesUseStableWidthAndNames](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L17) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [oversizedResultIsRejectedWithoutTruncation](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L28) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [unsafeFileNameCharactersAreRemoved](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L33) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [skyPaletteChangesShareImageAccent](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L38) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [incompleteFilterHidesCompletedTasksAndEmptyGroups](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L45) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [allCompleteIncompleteFilterRendersCompactCompletionCard](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L79) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [longInformationBodyIncreasesImageHeightWithoutTruncation](../app/src/test/java/com/ds/localtaskmanager/sharing/W24ShareImageRendererTest.kt#L91) | 保留；文件命名、尺寸上限、过滤与正文长度保留为分享改动补测；见断言局限。 |
| [deadline is consistently displayed to minutes](../app/src/test/java/com/ds/localtaskmanager/ui/DeadlineFormatterTest.kt#L7) | 保留；已合并原两个方法；整点、非整点和带秒输入均保留。 |
| [completion waits for in flight mood write and persists latest text exactly once](../app/src/test/java/com/ds/localtaskmanager/ui/execution/MoodSaveRaceRegressionTest.kt#L41) | 保留；阻塞写入与迟到输入的真实时序，不能用单纯防抖测试替代。 |
| [flush callback waits until newer mood text has been persisted](../app/src/test/java/com/ds/localtaskmanager/ui/execution/MoodSaveRaceRegressionTest.kt#L77) | 保留；阻塞写入与迟到输入的真实时序，不能用单纯防抖测试替代。 |
| [note is debounced and flush saves immediately](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L46) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [successful completion requests connected synchronization](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L69) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [mood text is debounced and latest edit is flushed before completion](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L90) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [mood save failure prevents completion and exposes error state](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L118) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [step information waits 500ms and confirmation uses the latest draft](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L133) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [counter writes are serialized and latest value is retained](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L152) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [timer failure remains pending and pause retries the same absolute draft](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L166) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [flush waits for blocked old write then persists newest revision before confirm](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L184) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [step save failure is retried and blocks confirmation](../app/src/test/java/com/ds/localtaskmanager/ui/execution/W22ExecutionViewModelTest.kt#L209) | 保留；保存节流、失败、确认锁定和回调入口分别保留；见并发断言局限。 |
| [newer completion is first when displayed times are in the same minute](../app/src/test/java/com/ds/localtaskmanager/ui/history/W23TimelineOrderingTest.kt#L9) | 保留；同分钟排序和积分修订说明保留为历史展示补测。 |
| [points revision identifies daily group and ungrouped scopes](../app/src/test/java/com/ds/localtaskmanager/ui/history/W23TimelineOrderingTest.kt#L31) | 保留；同分钟排序和积分修订说明保留为历史展示补测。 |
| [presentationAndPlainTextContainOnlyShareableFields](../app/src/test/java/com/ds/localtaskmanager/ui/result/W24ResultPresentationTest.kt#L15) | 保留；分享字段边界与积分格式保留为结果展示补测。 |
| [pointFormattingKeepsZeroVisible](../app/src/test/java/com/ds/localtaskmanager/ui/result/W24ResultPresentationTest.kt#L29) | 保留；分享字段边界与积分格式保留为结果展示补测。 |
| [sections use category order group order and hide cancelled instances](../app/src/test/java/com/ds/localtaskmanager/ui/today/W22TodayModelsTest.kt#L10) | 保留；分类和手动排序保留为今日页补测。 |
| [manual order wins then default ordering handles unsorted tasks](../app/src/test/java/com/ds/localtaskmanager/ui/today/W22TodayModelsTest.kt#L29) | 保留；分类和手动排序保留为今日页补测。 |
| [requestBodyWriteFailureBecomesRetryableAndDisconnects](../app/src/testConnected/java/com/ds/localtaskmanager/connected/CloudApiRateLimitTest.kt#L16) | 保留；HTTP 写失败、取消、429 和 Retry-After 对重试结果的影响不同。 |
| [repeatedLoginAttemptsWaitWithoutBlockingCodeVerification](../app/src/testConnected/java/com/ds/localtaskmanager/connected/CloudApiRateLimitTest.kt#L39) | 保留；HTTP 写失败、取消、429 和 Retry-After 对重试结果的影响不同。 |
| [retryAfterAcceptsHttpDateAndPrefersLongerValidDelay](../app/src/testConnected/java/com/ds/localtaskmanager/connected/CloudApiRateLimitTest.kt#L56) | 保留；HTTP 写失败、取消、429 和 Retry-After 对重试结果的影响不同。 |
| [serverRateLimitBlocksFurtherNetworkRequestsIncludingAuthenticatedCalls](../app/src/testConnected/java/com/ds/localtaskmanager/connected/CloudApiRateLimitTest.kt#L64) | 保留；HTTP 写失败、取消、429 和 Retry-After 对重试结果的影响不同。 |
| [responseCancellationIsPreservedAndNetworkFailureIsRetryableAndBothDisconnect](../app/src/testConnected/java/com/ds/localtaskmanager/connected/CloudApiRateLimitTest.kt#L98) | 保留；HTTP 写失败、取消、429 和 Retry-After 对重试结果的影响不同。 |
| [unauthorizedSessionFailureRequiresLocalPurge](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L17) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [retryableTransportFailureKeepsOfflineCache](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L23) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [validAccessTokenDoesNotRotateDuringManualSync](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L29) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [nearlyExpiredOrMalformedAccessTokenIsRefreshed](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L35) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [automaticSyncRunsOnlyWhenTheCachedDataIsStale](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L42) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [aPreviouslyUnseenNotificationRequestsAFullSync](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L52) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [onlyActiveAssignmentsMaterializeCloudTasks](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L60) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [informationResultCarriesOnlyTheSubmittedUserContent](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L68) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [moodResultCarriesSubmittedAnswerOnlyForCompletedInstance](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L105) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [stepsResultContainsAllFiveLeafTypesAndNoSkippedAnswer](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L129) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [pendingStepsNeverPretendToBeConfirmedAndMissedHasNoStepResults](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L157) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [newerUndoSnapshotCannotConstructOldCompletedResult](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedRuntimeTest.kt#L167) | 保留；身份失效、自动触发和完成/撤销载荷隐私边界分别保留。 |
| [request builds connected exponential identity-only work and enqueues append-or-replace](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncCoordinatorTest.kt#L20) | 保留；保留请求身份、网络与退避配置；不能替代设备系统调度验证。 |
| [migrates v1 sync state](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncDatabaseMigrationTest.kt#L36) | 保留；联网会话、队列和重试元数据须在数据库升级后保留。 |
| [startup recovery queues completed result and occurrence once without draft](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L56) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [background identity mismatch does no network and leaves newer session untouched](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L78) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [background run with no pending work completes without network](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L115) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [retry deadline survives engine recreation without network](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L135) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [lost command response retries identical command and duplicate closes outbox](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L158) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [foreground and background synchronization share one in-flight request](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L223) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [rejected command is permanently retained and is not retried as a command](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L280) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [account retry-after persists deadline across engine recreation](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L297) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [cancellation during account response propagates and preserves pending command](../app/src/testConnected/java/com/ds/localtaskmanager/connected/ConnectedSyncEngineTest.kt#L323) | 保留；恢复、身份隔离、回执丢失、限流、拒绝和并发均为独立故障。 |
| [diagnostic references remain correlatable without exposing original values](../app/src/testConnected/java/com/ds/localtaskmanager/connected/SyncTraceTest.kt#L13) | 保留；默认关闭及启用后脱敏均须验证；非法明文测试须另以诊断 flag 开启运行。 |
| [unsafe diagnostic detail is rejected without failing the operation](../app/src/testConnected/java/com/ds/localtaskmanager/connected/SyncTraceTest.kt#L24) | 保留；默认关闭及启用后脱敏均须验证；非法明文测试须另以诊断 flag 开启运行。 |
| [launchShowsTodayScreenAndPrimaryNavigation](../app/src/androidTest/java/com/ds/localtaskmanager/AppSmokeTest.kt#L21) | 保留；真实 Activity 导航与包差异；三个离线入口在联网变体的既有 assume 不改动。 |
| [importFabOpensDst1Dialog](../app/src/androidTest/java/com/ds/localtaskmanager/AppSmokeTest.kt#L36) | 保留；真实 Activity 导航与包差异；三个离线入口在联网变体的既有 assume 不改动。 |
| [settingsShowsUserInitiatedReminderPermissionEntry](../app/src/androidTest/java/com/ds/localtaskmanager/AppSmokeTest.kt#L44) | 保留；真实 Activity 导航与包差异；三个离线入口在联网变体的既有 assume 不改动。 |
| [systemBackFromSettingsReturnsToProfile](../app/src/androidTest/java/com/ds/localtaskmanager/AppSmokeTest.kt#L53) | 保留；真实 Activity 导航与包差异；三个离线入口在联网变体的既有 assume 不改动。 |
| [homeExposesExportAndRestore](../app/src/androidTest/java/com/ds/localtaskmanager/ui/backup/W31BackupScreenTest.kt#L26) | 保留；导出、恢复模式选择和冲突选择操作入口，与 Room 恢复测试不同。 |
| [restorePreviewDefaultsToMergeAndAllowsReplace](../app/src/androidTest/java/com/ds/localtaskmanager/ui/backup/W31BackupScreenTest.kt#L41) | 保留；导出、恢复模式选择和冲突选择操作入口，与 Room 恢复测试不同。 |
| [conflictCanChooseBackupVersion](../app/src/androidTest/java/com/ds/localtaskmanager/ui/backup/W31BackupScreenTest.kt#L51) | 保留；导出、恢复模式选择和冲突选择操作入口，与 Room 恢复测试不同。 |
| [midpointTapCommitsNeutralAnswer](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L33) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [draggingToEitherEndSnapsToFivePointScale](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L51) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [accessibilityProgressActionCommitsDiscreteRatings](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L72) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [everyVisibleTickAcceptsDirectTouch](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L91) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [focusedMoodInputKeepsCompletionActionReachable](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L115) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [readOnlyMoodShowsAnswerAndTextWithoutEditor](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/MoodSectionTest.kt#L147) | 保留；点选、拖动、读屏、键盘与只读状态为不同交互通道。 |
| [currentStepUnlocksInOrderAndOnlyOptionalStepsCanBeSkipped](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/StepsSectionTest.kt#L21) | 保留；步骤锁定、跳过和只读正文交互保留；见撤销断言局限。 |
| [readOnlyStepsHaveNoEditingActionsAndConfirmedAnswersCanBeUndone](../app/src/androidTest/java/com/ds/localtaskmanager/ui/execution/StepsSectionTest.kt#L47) | 保留；步骤锁定、跳过和只读正文交互保留；见撤销断言局限。 |
| [searchFiltersCalendarAndTaskNavigationAreReachable](../app/src/androidTest/java/com/ds/localtaskmanager/ui/history/W23HistoryScreenTest.kt#L32) | 保留；历史筛选导航、只读执行与可编辑本机备注的 UI 集成。 |
| [historicalDetailIsReadOnlyButOrdinaryNoteRemainsEditable](../app/src/androidTest/java/com/ds/localtaskmanager/ui/history/W23HistoryScreenTest.kt#L71) | 保留；历史筛选导航、只读执行与可编辑本机备注的 UI 集成。 |
| [populatedProfileShowsSignedPointsTrendAndArchivedEntry](../app/src/androidTest/java/com/ds/localtaskmanager/ui/profile/W25ProfileScreenTest.kt#L24) | 保留；有数据、空/错误和联网通知入口条件各自保留。 |
| [profileShowsFailureAndEmptyStates](../app/src/androidTest/java/com/ds/localtaskmanager/ui/profile/W25ProfileScreenTest.kt#L44) | 保留；有数据、空/错误和联网通知入口条件各自保留。 |
| [connectedProfileShowsSharedNotificationEntryOnlyWhenEnabled](../app/src/androidTest/java/com/ds/localtaskmanager/ui/profile/W25ProfileScreenTest.kt#L63) | 保留；有数据、空/错误和联网通知入口条件各自保留。 |
| [appearanceAndReducedMotionActionsAreExposed](../app/src/androidTest/java/com/ds/localtaskmanager/ui/settings/W26SettingsScreenTest.kt#L23) | 保留；主题、减少动画和隐私重置回调属于设置交互覆盖。 |
| [confirmedPrivacyCanBeReset](../app/src/androidTest/java/com/ds/localtaskmanager/ui/settings/W26SettingsScreenTest.kt#L54) | 保留；主题、减少动画和隐私重置回调属于设置交互覆盖。 |
| [resultShowsSnapshotAndTasksAreReadOnly](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L43) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [pullGestureOpensResult](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L55) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [slowOneHundredTenDpPullOpensResultOnRelease](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L72) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [endGestureClosesResult](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L94) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [emptyResultHasNoShareActions](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L107) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [informationActionsAreAvailableForNonEmptyBody](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L113) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [sharePreviewActionsAreFullyVisible](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L140) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [resultSharePreviewOffersTaskFilters](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L159) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [cachedShareImageUsesContentUri](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L179) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [generatedImageCanBeSavedToGalleryOnModernAndroid](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L188) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [oneHundredTaskImageRendersWithinTwoSeconds](../app/src/androidTest/java/com/ds/localtaskmanager/ui/today/W24ResultScreenTest.kt#L201) | 保留；手势、分享、URI、系统相册及真实设备渲染互不等价；见空状态断言局限。 |
| [BackupHomeScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/backup/W31BackupScreenshotTest.kt#L17) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [BackupRestorePreviewScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/backup/W31BackupScreenshotTest.kt#L24) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [BackupConflictScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/backup/W31BackupScreenshotTest.kt#L38) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [BackupResultScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/backup/W31BackupScreenshotTest.kt#L53) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [MoodSectionUnselectedScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/execution/MoodSectionScreenshotTest.kt#L17) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [MoodSectionSelectedScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/execution/MoodSectionScreenshotTest.kt#L24) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [MoodSectionReadOnlyDarkScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/execution/MoodSectionScreenshotTest.kt#L31) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [MoodSectionLargeFontScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/execution/MoodSectionScreenshotTest.kt#L41) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [MoodTaskDetailScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/execution/MoodSectionScreenshotTest.kt#L48) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [HistoryPopulatedScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/history/HistoryPreviewScreenshotTest.kt#L8) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [HistoryEmptyScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/history/HistoryPreviewScreenshotTest.kt#L15) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [HistoryFilteredEmptyScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/history/HistoryPreviewScreenshotTest.kt#L22) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [TodayPageScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/navigation/TodayPagePreviewScreenshotTest.kt#L7) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [ProfilePopulatedScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/profile/W25ProfileScreenshotTest.kt#L24) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [ProfileEmptyScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/profile/W25ProfileScreenshotTest.kt#L46) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [ProfileSettingsEntryScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W26SettingsScreenshotTest.kt#L15) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [SettingsLightScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W26SettingsScreenshotTest.kt#L24) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [SettingsDarkScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W26SettingsScreenshotTest.kt#L33) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [SettingsLargeFontScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W26SettingsScreenshotTest.kt#L42) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [SkyPaletteLightScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W33PaletteScreenshotTest.kt#L11) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [SkyPaletteDarkScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/settings/W33PaletteScreenshotTest.kt#L16) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [TodayResultScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/today/W24PreviewScreenshotTest.kt#L7) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [TodayResultEmptyScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/today/W24PreviewScreenshotTest.kt#L12) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [ResultShareImageScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/today/W24PreviewScreenshotTest.kt#L17) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
| [InformationShareImageScreenshot](../app/src/screenshotTest/kotlin/com/ds/localtaskmanager/ui/today/W24PreviewScreenshotTest.kt#L22) | 保留；保留该命名状态的视觉基线；截图检测布局与主题差异，交互测试不能替代像素对比。 |
