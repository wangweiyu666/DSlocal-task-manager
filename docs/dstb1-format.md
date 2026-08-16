# DSTB1 v1 文件格式

DSTB1 是 Sub 端的本地备份容器。多字节整数统一采用无符号小端序；字符串统一为 UTF-8。

## 二进制布局

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

## 元数据

元数据 JSON 固定包含：

- `createdAtEpochMillis`：快照创建时间；
- `appVersion`：来源应用版本；
- `sourceTimeZone`：来源 IANA 时区；
- `payloadSchemaVersion`：业务 JSON 版本，当前为 `2`；读取端继续接受 `1`；
- `counts`：积分组、任务定义、实例、积分流水、操作记录和结果版本数量。

元数据不得包含设备名称、型号、账号、文件名或外部路径。数量摘要必须与解压后的实际集合一致。

## 业务 JSON v2

顶层字段顺序为：

```text
schemaVersion, settings, profiles, importBatches, groups,
definitions, definitionSteps, recurrenceExceptions, instances, instanceSteps,
progress, information, notes, ledger, actionLogs, resultRevisions
```

集合按各自主键排序。JSON 使用显式默认值和 `null`，不写入无意义空白。格式 DTO 与 Room 实体分离；新增 Room 字段不能在未提升业务 JSON 版本时自动进入文件。

`settings` 只允许 `themeMode`、`reduceMotion` 和 `lastStatisticsPeriod`。`recurrenceExceptions` 保存重复任务单日例外；`instances.singleDayAdjusted` 保存已生成实例的来源快照。系统提醒记录不属于业务 JSON。业务 JSON v1 迁移时这两个字段按空列表和 `false` 处理。

## 压缩与限制

- 使用标准 zlib 包装的 Deflate 数据；
- 外部文件上限 100 MB；
- 元数据上限 1 MB；
- 解压后业务 JSON 上限 500 MB；
- 解压过程中必须同时检查实际输出和文件头声明长度，禁止先无界解压后校验。

## 兼容与错误

- 魔数错误：不是 DSTB1 文件；
- CRC、长度或 zlib 错误：文件损坏；
- 格式版本高于当前支持版本：提示升级应用；
- 已知旧版本：先迁移到当前业务 DTO，再执行统一校验；
- 未知字段、重复主键、无效引用或非法值：拒绝整个文件，不做部分恢复。

CRC32 不提供身份认证。任何能够改写文件的人都能重新计算 CRC32，因此恢复流程仍必须执行完整业务校验。
