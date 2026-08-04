# DSTB1 测试向量

二进制资产位于 `protocol-test-vectors/dstb1/`。合法样例由当前稳定编码器生成，记录顺序和压缩结果必须保持稳定。

| 文件 | 预期 |
|---|---|
| `minimal-valid.dstb` | 空业务数据，校验和解析成功 |
| `all-records-valid.dstb` | 覆盖全部 13 类备份记录，校验、解析和完全替换成功 |
| `crc-corrupt.dstb` | CRC32 不匹配，拒绝且不修改数据库 |
| `truncated.dstb` | 文件截断，拒绝且不修改数据库 |
| `future-version.dstb` | 格式版本高于当前版本，提示升级应用 |
| `duplicate-key.dstb` | 业务 JSON 含重复主键，拒绝且不修改数据库 |

测试还需动态构造 100 MB 文件边界、500 MB 解压边界和压缩炸弹场景，避免在仓库中提交超大资产。
