# DSTB1 备份格式

当前外层格式 **1**、业务 payload **5**，读取 payload 1～5；主 Room 库 **9**。三个版本不能混用。实现见 [DTO](../app/src/main/java/com/ds/localtaskmanager/backup/BackupModels.kt)、[编码器](../app/src/main/java/com/ds/localtaskmanager/backup/DstbCodec.kt)、[校验器](../app/src/main/java/com/ds/localtaskmanager/backup/BackupValidator.kt)。Web DSDOM 配置备份和云 DSEXPORT 导出不是 DSTB1。

## 二进制布局

多字节整数为无符号小端，JSON 为 UTF-8，压缩使用标准 zlib/DEFLATE。


| 顺序 | 长度 | 内容 |
|---|---:|---|
| 1 | 5 | ASCII 魔数 `DSTB1` |
| 2 | 2 | 格式版本，当前为 `1` |
| 3 | 4 | 元数据 JSON 字节长度 |
| 4 | 可变 | 元数据 JSON |
| 5 | 8 | zlib 数据字节长度 |
| 6 | 8 | 解压后业务 JSON 字节长度 |
| 7 | 可变 | zlib 数据 |
| 8 | 4 | CRC32 |

CRC32 覆盖从魔数开始到 zlib 数据末尾的全部字节，不包含末尾 CRC32 字段本身。


文件上限 100 MiB、元数据 1 MiB、解压业务 500 MiB；声明长度和实际输出同时有界检查。CRC32 不提供加密或身份认证。未来外层版本提示升级，损坏整文件拒绝。

## 元数据与 payload 5

元数据字段为 `createdAtEpochMillis,appVersion,sourceTimeZone,payloadSchemaVersion,counts`；counts 核对组、定义、实例、流水、日志和结果数量，不包含设备名称、型号、账号或路径。payloadSchemaVersion 必须与业务 schemaVersion 一致。

业务顶层顺序如下，集合按主键排序，显式输出默认值和 null；DTO 与 Room 实体分离：

```text
schemaVersion, settings, profiles, importBatches, groups,
definitions, definitionSteps, recurrenceExceptions, instances, instanceSteps,
progress, information, moods, notes, ledger, actionLogs, resultRevisions
```

| v5 内容 | 位置 |
| --- | --- |
| `executionConfigJson` 通知/选项配置 | 定义、定义步骤、实例、实例步骤 |
| `conditionStepId,conditionOptionId` | 定义步骤、实例步骤 |
| `selectedOptionId` | 实例步骤、执行进度 |
| `awardedPoints` | 完成实例的实际得分 |
| 稳定 stepId、顺序、类型、状态及答案 | 同源实例步骤，PENDING / CONFIRMED / SKIPPED / NOT_APPLICABLE |

选项必须存在于快照，条件、答案与分支状态须一致；完成实际分值等于基础分＋适用确认选项积分。不适用草稿不能冒充提交，通知以确认状态记录已知晓。心情草稿 rating 可空，提交为 1～5，text 最多 2000 码点；完成须有有效提交，其余状态不得假报提交。

settings 仅含 themeMode、reduceMotion、lastStatisticsPeriod；隐私确认、系统权限、提醒安排、图片和缓存不备份。日期例外保存在 recurrenceExceptions，单日标记在 instances.singleDayAdjusted。

## 兼容、恢复与合并

| 业务版本 | 引入内容 |
| --- | --- |
| 1 | 原始业务；读取补空例外和 false 单日标记 |
| 2 | 重复日期例外 |
| 3 | 心情提交；v1/v2 不得带 MOOD |
| 4 | STEPS 稳定身份、叶子配置和整组进度 |
| 5 | 通知、单选、条件、选择和实际得分；旧完成缺失得分快照按原基础分读取 |

旧版本不能假报新版字段或能力；旧步骤缺失 ID 按旧语义确定性恢复，Room 8→9 不自动转换旧任务。未知字段、重复主键、非法值/引用、分支或积分不一致使整文件失败，不跳过坏记录。

完全替换先保留内部回滚快照，事务恢复业务与可迁移设置，失败回滚；进程中断后启动恢复，提醒失败单独重试。合并保留本机设置，按更新时间/冲突选择确定定义与实例来源；等时不同内容默认本机并允许选择备份。

步骤定义、例外、实例步骤与依赖答案按选定来源整体恢复，不逐条混合不同版本分支。完成答案与实际得分保持快照，新撤销不被旧完成覆盖；独立可变记录按仓库合并规则选取。不可变流水、日志和结果修订按 ID 去重，同 ID 内容不同拒绝。恢复后重算派生状态与结果、重建通知，计时暂停、过期提醒不补发。

## 兼容样例

`protocol-test-vectors/dstb1/` 中 minimal-valid、all-records-valid 用于旧格式合法读取；crc-corrupt、truncated、future-version、duplicate-key 用于损坏、截断、未来外层和重复键。不要把旧文件称为 v5 全字段样例。v5 由当前 DTO 和备份测试构造，超大边界动态生成；执行范围见 [测试指南](minimal-testing.md)，实际结果见 [验证档案](verification-records.md)。
