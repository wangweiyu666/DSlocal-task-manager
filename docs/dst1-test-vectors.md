# DST1 v1 测试向量规范

> Schema：`docs/dst1-schema.json`  
> 共享清单：`protocol-test-vectors/manifest.json`  
> 规范版本：1

## 1. 目的

共享向量是 Android Sub 与未来 Dom 网页实现之间的协议契约。每个向量同时记录：

- DST1 v1 规范结果 `spec`；
- 当前 Android 构建结果 `android`；
- 失败时稳定的错误代码和字段路径。

`x`、`h`、`u` 均属于 DST1 v1，因此合法向量的 `spec.result` 为 `VALID`。W10 起 Android 支持 `u.k=1/2/3`，W11 起支持 `x.f=1/2`，W21 起实现既有 `h`；这些合法向量均标记为 Android `VALID`。非法组合必须返回具体协议错误。

## 2. 清单结构

```json
{
  "id": "counter",
  "source": {"kind":"json","path":"valid/counter.json"},
  "spec": {"result":"VALID"},
  "android": {"result":"VALID"}
}
```

`source.kind`：

| 值 | 含义 |
|---|---|
| `json` | 直接验证解压后的 JSON |
| `dst1` | 验证完整信封；可用 `decodedJson` 指定解压后的黄金 JSON |
| `generator` | 按下文的确定性规则生成大型边界输入 |

所有提交到 `valid/`、`future-valid/`、`invalid/` 的 `.json` 与 `.dst1` 文件都必须在清单中出现。Android 的统一加载测试会检查该条件。

## 3. 稳定错误模型

错误由三部分组成：

- `code`：稳定、与本地化无关；
- `path`：短字段 JSON 路径，顶层信封使用 `$`；
- `message`：面向用户或开发者的中文说明，不作为测试契约。

### 3.1 信封与解码错误

| 代码 | 典型路径 | 含义 |
|---|---|---|
| `INPUT_TOO_LARGE` | `$` | 输入超过 128 KiB 字符上限 |
| `INVALID_ENVELOPE` | `$` | 不是三段 `DST1.payload.checksum` |
| `INVALID_CHECKSUM_FORMAT` | `$.checksum` | CRC32 不是 8 位大写十六进制 |
| `INVALID_BASE64URL` | `$.payload` | payload 不是无填充 Base64URL |
| `COMPRESSED_DATA_TOO_LARGE` | `$.payload` | 解码后的压缩数据超过 96 KiB |
| `CHECKSUM_MISMATCH` | `$.checksum` | CRC32 不一致 |
| `DECOMPRESSION_FAILED` | `$.payload` | 不是有效的 zlib 数据 |
| `JSON_TOO_LARGE` | `$.json` | 解压后的 JSON 超过 256 KiB |
| `INVALID_UTF8` | `$.json` | 解压字节不是严格 UTF-8 |

128 KiB 信封上限包含前缀、分隔符和 CRC32。由于 Base64URL 膨胀，任何真正超过 96 KiB 的 payload 也必然先超过当前信封上限；因此清单中的 `compressed-over-limit-precedence` 固定预期为 `INPUT_TOO_LARGE`。`COMPRESSED_DATA_TOO_LARGE` 仍保留为防御性检查。

### 3.2 JSON 与字段错误

| 代码 | 含义 |
|---|---|
| `INVALID_JSON` | JSON 语法错误 |
| `TYPE_MISMATCH` | 对象、数组、字符串或整数类型错误 |
| `UNKNOWN_FIELD` | 出现不在对象白名单中的字段 |
| `REQUIRED_FIELD_MISSING` | 缺少必填字段 |
| `INVALID_VALUE` | 枚举、ID 或其他离散值非法 |
| `VALUE_OUT_OF_RANGE` | 字符数、数值或集合数量越界 |
| `INVALID_DATE` | 日期、日期时间或时间格式/取值非法 |
| `NON_CANONICAL_TEXT` | 普通文本不是 Unicode NFC |
| `DUPLICATE_VALUE` | ID、数组值重复，或要求排序的集合不规范 |
| `CONFLICTING_FIELDS` | 互斥字段或跨字段组合冲突 |
| `EMPTY_OPERATION` | 批次或积分组没有实际更新 |
| `CAPABILITY_NOT_IMPLEMENTED` | 协议合法，但当前 Android 尚未实现该能力 |

未知字段没有忽略白名单。顶层、积分组、任务、步骤、重复规则和执行方式对象均设置 `additionalProperties: false`。

## 4. 文本规范

- 普通文本先去除首尾无意义空白；
- 裁剪后的文本必须已经是 NFC，否则返回 `NON_CANONICAL_TEXT`；
- 不自动把 NFD/NFKD 等形式转换为 NFC；
- 长度按 Unicode 码点计数，不按 UTF-16 code unit 计数；
- ID 仅按 16 位 Base64URL 规则校验，不裁剪、不规范化、不改变大小写；
- 空字符串仅用于 Schema 明确允许的字段：顶层 `d`、积分组 `cm/im`、任务 `d/m`。

JSON Schema 的 `maxLength` 按 Unicode 字符计算，但 NFC、首尾裁剪、真实日历日期、数组排序和跨记录唯一性仍由实现层验证。

## 5. 无法只靠 Schema 表达的约束

实现与向量必须额外验证：

- 日期和时间是真实取值；
- `w` 唯一且升序，`h` 唯一且降序；
- 同一批次的 `groupId` 唯一；
- 分组与未分组任务合计不超过 100，且 `taskId` 唯一；
- `z` 不重复，且不能与任务列表包含同一 `taskId`；
- 非 NFC 文本拒绝；
- 信封、压缩数据与解压 JSON 的大小限制；
- 当前 Android 对协议合法但尚未实现能力的差异化结果。

## 6. 确定性生成向量

| 生成器 | 构造规则 | 预期重点 |
|---|---|---|
| `invalid-envelope` | 使用错误主版本前缀 | `INVALID_ENVELOPE` |
| `invalid-checksum-format` | 使用小写 CRC32 | `INVALID_CHECKSUM_FORMAT` |
| `invalid-base64url` | payload 带 `=` 填充 | `INVALID_BASE64URL` |
| `decompression-failed` | 正确 CRC32 包裹非 zlib 字节 | `DECOMPRESSION_FAILED` |
| `invalid-json` | 压缩语法错误的 UTF-8 JSON | `INVALID_JSON` |
| `top-level-type` | 顶层 JSON 为数组 | `TYPE_MISMATCH` |
| `unsupported-version` | JSON `v` 不为 1 | `INVALID_VALUE` |
| `input-over-limit` | 生成 128 KiB + 1 字符输入 | `INPUT_TOO_LARGE` |
| `compressed-over-limit` | 生成 96 KiB + 1 字节 payload 并封装 | 信封上限优先 |
| `json-at-limit` | 合法最小 JSON 后补 JSON 空白至 256 KiB | 合法通过 |
| `json-over-limit` | 同上补至 256 KiB + 1 | `JSON_TOO_LARGE` |
| `invalid-utf8` | zlib 压缩固定非法 UTF-8 字节 `C3 28` | `INVALID_UTF8` |
| `too-many-steps` | 确定性生成 51 个步骤 | 集合上限 |
| `task-name-over-limit` | 生成 101 个码点任务名 | 文本上限 |

跨语言实现必须生成语义等价输入；压缩字节无需逐字节相同。

### 6.1 日期指令语义

- 首次导入的新任务缺少 `y`时，按 `l`或导入时任务日推导归属日期；
- 更新已有同 `taskId`任务时，缺少 `y`保留已保存的归属日期；
- 只有显式且不同的 `y`构成日期迁移；
- 解析 `l`得到的推导日期不得迁移已有任务；
- 测试实现必须在解析模型中保留 `y`和 `l`是否显式出现，不能只保留最终日期时间值。

## 7. 错误优先级

当一个输入同时包含多个问题时，固定采用以下优先级：

1. 输入长度和外层格式；
2. Base64URL、压缩大小、CRC32、zlib、UTF-8；
3. JSON 语法和顶层类型；
4. 未知字段；
5. 必填字段、类型和单字段取值；
6. 嵌套对象，按 `g`、`t`、`z` 及数组顺序深度优先；
7. 跨对象数量、重复和冲突约束；
8. 当前实现能力检查。

普通向量保持“一例一错”。只有带 `precedence` 含义的案例故意组合多个失败条件。

## 8. 变更规则

增加或修改 DST1 字段时，必须在同一变更中同步：

1. `docs/dst1-schema.json`；
2. 本规范与错误代码表；
3. `protocol-test-vectors/manifest.json`；
4. 至少一个合法向量和相关非法/边界向量；
5. Android 解析器及统一加载测试；
6. 未来 Dom 编码器和同一清单的加载测试。

只有不兼容的字段或业务语义变化才升级 DST 主版本。能力从 `CAPABILITY_NOT_IMPLEMENTED` 变为支持时，协议仍保持 DST1 v1，只更新 Android 预期。
