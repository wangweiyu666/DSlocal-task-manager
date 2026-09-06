# DSTB1 v1 文件格式

DSTB1 是 Sub 端的本地备份容器。多字节整数统一采用无符号小端序；字符串统一为 UTF-8。Room 8 的步骤定义保存稳定 `stepId`、叶子执行配置、每实例草稿/进度和提交快照；恢复时按完整来源修订选择，不逐条拼接不同版本步骤。日期与 STEPS 的自动化验证记录见[验证记录](date-steps-validation.md)。

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
- `payloadSchemaVersion`：业务 JSON 版本，当前为 `4`；读取端继续接受 `1`、`2`、`3`；
- `counts`：积分组、任务定义、实例、积分流水、操作记录和结果版本数量。

元数据不得包含设备名称、型号、账号、文件名或外部路径。数量摘要必须与解压后的实际集合一致。

## 业务 JSON v4

顶层字段顺序为：

```text
schemaVersion, settings, profiles, importBatches, groups,
definitions, definitionSteps, recurrenceExceptions, instances, instanceSteps,
progress, information, moods, notes, ledger, actionLogs, resultRevisions
```

集合按各自主键排序。JSON 使用显式默认值和 `null`，不写入无意义空白。格式 DTO 与 Room 实体分离；新增 Room 字段不能在未提升业务 JSON 版本时自动进入文件。

`settings` 只允许 `themeMode`、`reduceMotion` 和 `lastStatisticsPeriod`。`recurrenceExceptions` 保存重复任务单日例外；`instances.singleDayAdjusted` 保存已生成实例的来源快照。系统提醒记录不属于业务 JSON。业务 JSON v1 迁移时这两个字段按空列表和 `false` 处理。

`moods` 按任务 ID、实例键保存 `rating`（草稿可为 null，否则 1～5）、`text`（最多 2000 个 Unicode 字符）、创建／更新时间和可空提交时间。旧 v1/v2 读取时补空集合，旧版本不得声明 MOOD 或包含心情记录。已完成 MOOD 实例必须有已提交答案；其他状态不得标记已提交。合并恢复时，已完成答案跟随所选实例快照，其他心情内容使用更新时间较新的记录；替换恢复完整还原。

v4 的步骤记录携带稳定 16 字符 `stepId`、叶子执行配置、实例状态（`PENDING`/`CONFIRMED`/`SKIPPED`）及计数、计时、信息、心情答案。步骤状态按父实例整体选择和恢复，不按位置拼接不同版本。Room 8 的数据库迁移只增加稳定步骤定义、例外和结果快照所需字段，迁移过程不把旧任务按位置重写为 STEPS；只有管理员明确编辑并保存旧定义时才执行确定性 ID 转换。

备份格式 v4 与旧版本保持读取兼容：缺失的步骤 ID、叶子执行配置和例外字段按旧格式语义解释，写出时使用完整的当前结构。导入合并先按任务/实例及来源修订选择完整记录，再恢复同一来源的整组步骤定义、例外和实例进度；不能把不同修订的步骤逐条拼接，也不能让旧完成状态覆盖较新的撤销或定义。

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

## 测试向量

二进制资产位于 `protocol-test-vectors/dstb1/`。合法样例由稳定编码器生成，记录顺序和压缩结果必须保持稳定。

| 文件 | 预期 |
| --- | --- |
| `minimal-valid.dstb` | 空业务数据，校验和解析成功 |
| `all-records-valid.dstb` | 覆盖全部备份记录，校验、解析和完全替换成功 |
| `crc-corrupt.dstb` | CRC32 不匹配，拒绝且不修改数据库 |
| `truncated.dstb` | 文件截断，拒绝且不修改数据库 |
| `future-version.dstb` | 格式版本过高，提示升级应用 |
| `duplicate-key.dstb` | 业务 JSON 含重复主键，拒绝且不修改数据库 |

测试还需动态构造 100 MB 文件边界、500 MB 解压边界和压缩炸弹场景，避免在仓库中提交超大资产。
