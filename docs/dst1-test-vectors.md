# DST1 / DST1.1 契约与向量

字段类型以 [JSON Schema](dst1-schema.json) 为准；Web/Cloud 共用 [types](../shared/protocol/types.ts)、[validation](../shared/protocol/validation.ts) 和 [execution](../shared/protocol/execution.ts)，Android 独立解析。状态、日期和积分只在 [产品规则](preview.md) 维护。

## 信封与兼容

无例外使用 `DST1.<payload>.<checksum>` 与 JSON `v:1`；有例外使用 `DST1.1.<payload>.<checksum>` 与 `v:1,sv:1,e:[...]`。payload 是紧凑 UTF-8 JSON 经 zlib/DEFLATE 后的无填充 Base64URL；checksum 是实际压缩字节的 CRC32，8 位大写十六进制。禁止 raw DEFLATE、gzip 和预设字典；建议压缩 level 6，不要求不同平台压缩字节相同。

编码按固定字段顺序输出，读取不依赖顺序。CRC32 只检测损坏，不认证发送者或防恶意改写。依次检查大小、信封、解码、CRC、有界解压、严格 UTF-8、JSON 和完整业务规则；任一失败整批拒绝。未知字段、版本和类型不能降级为普通任务；旧客户端使用新能力须升级。

## 字段与更新

| 对象 | 字段与语义 |
| --- | --- |
| 批次 | 必需 `v,b`；可选 `sv,d,m,g,t,z,e`；`d` 更新布置者名，批次说明 `m` 单独不构成操作 |
| 组 | 必需 `i`；`n,cm,im,t` 至少一项更新；省略保留，`cm/im` 空字符串恢复默认 |
| 任务 | 必需 `i,n,r`；可选 `d,y,l,p,o,s,x,m,h,u,a`；完整替换，但已有任务省略 `y` 保留原日期 |
| 步骤 | `n,r`；新 STEPS 必须稳定 `i`，可选叶子 `u` 与条件 `c`；旧无 ID 步骤仅用于兼容 |
| 重复 | `f=1` 每日 / `f=2` 每周；可选 `s,e,c,w,t`；结束与次数互斥，每周必须 `w`、每日禁止 `w` |
| 单日例外 | 必需 `i,y`；独占 `c:1` 撤销，或覆盖 `n,r,d,l,p,o,s,m,h,u,a`；只有 `i,y` 清除例外 |

批次/任务/组/步骤/选项 ID 恰为 16 位 `[A-Za-z0-9_-]`、区分大小写；新 ID 使用 12 字节安全随机数，不把 `taskId` 等占位词写入真实 JSON。相同批次 ID 防重复导入。非例外清空语义的空数组省略。

顶层 `d:""` 清除布置者名称；任务省略描述/步骤/提醒/重复恢复为空，积分为 0、排序/完成提示/执行恢复默认、截止为次日 04:00。新任务无 `y` 的接收端推导与 Web 布置时冻结自然日是不同入口，见产品规则。

例外在基础更新后求完整有效定义，同日新指令完整替换旧例外；省略继承，`l/o/m/u:null` 为无截止/默认排序/默认提示/直接执行，`d:""`、`s:[]`、`h:[]` 清空。不能改 ID、组、重复规则或计划日；目标须是有效重复任务，引用存储模板时接收端继续验证有效结果。

任务 `a` 为一个自定义提醒：临时任务用 `YYYY-MM-DDTHH:mm`，重复任务用 `HH:mm`，省略清除。例外 `a` 用完整日期时间，`null` 清除、省略继承。与 `h` 独立；无截止可用，有截止时须校验完整定义及例外覆盖后的时间关系。日期省略且需存储上下文时，在接收端解析有效任务日后校验。旧客户端按未知字段拒绝，不能静默忽略。

## 执行、稳定步骤与条件

| `u.k` | 精确配置 |
| --- | --- |
| 省略 | 直接完成，无 k=0 |
| 1 | `{k:1,a:1或2,v:1～999}` |
| 2 | `{k:2,v:1～3600}`，单位秒 |
| 3 / 4 | `{k:3}` 信息 / `{k:4}` 心情，无其他字段 |
| 5 | `{k:5}`，任务带 1～50 个唯一步骤 ID |
| 6 | `{k:6,t:正文}`，非空 NFC，最多 2000 码点 |
| 7 | `{k:7,o:[{i:ID,n:名称,p:积分}]}`，2～50 个唯一选项；名称非空 NFC、最多 100 码点，积分 0～9999 整数 |

| 8 | `{k:8}` 体温询问，无额外配置；答案为 350～420 的整数十分之一摄氏度 |

步骤叶子允许省略或 k=1～4、6、7、8，禁止嵌套 k=5。通知、单选、体温和条件步骤要求任务为 STEPS。旧任务级执行与旧步骤兼容读取，不在升级时自动转换。

旧步骤明确转换时用 `s`＋taskId 前 12 字符＋原始零基位置的三位小写 base36；旧任务级执行追加步为 `r`＋taskId 前 12 字符＋`000`。生成后持久化，改名/排序不重生 ID；超 50 步要求调整，不丢旧要求。

条件 `c:{s:前置步骤ID,o:选项ID}` 每步最多一个，只引用同任务前面的单选和有效选项；拒绝缺失、跨任务、向后/循环引用。基础＋日期例外也必须合法。仅适用且已确认前置选择激活分支；等待、跳过、不适用不可混同。定义不携带运行答案；上行结果见 [联网指南](connected.md#任务分配与结果)。

## 限额与跨字段校验

| 内容 | 上限 |
| --- | --- |
| 信封字符 / 压缩字节 / 解压 JSON 字节 | 128 Ki / 96 KiB / 256 KiB |
| 组 / 总任务 / 撤销 / 例外 / 每任务步骤 | 50 / 100 / 100 / 100 / 50 |
| 布置者、组名称 / 任务、步骤、选项名称 | 50 / 100 码点 |
| 批次说明、完成提示、组文案 / 描述、执行正文 | 500 / 2000 码点 |
| 截止前提醒 h | 最多 5 个，0～10080 分钟，唯一降序且有有效截止 |
| 基础/选项积分 / 重复次数 / 排序 | 0～9999 / 1～2147483647 / 有符号 32 位整数 |

按 Unicode 码点计数；普通文本必须 NFC，读取不自动接受 NFD，ID 不裁剪、不改大小写。空字符串仅用于明确清空字段。真实日历日期、星期唯一升序、提醒唯一降序、组/任务跨数组唯一、撤销与更新互斥、分支及例外有效性都要额外校验，不能只检查 Schema。

## 稳定错误

错误为 `code,path,message`；前两者是契约，中文 message 可变。普通向量一例一错，precedence 用例固定多错优先级：信封/大小→解码→JSON/字段→嵌套→跨对象规则。

### 信封错误


| 代码 | 典型路径 | 含义 |
|---|---|---|
| `INPUT_TOO_LARGE` | `$` | 输入超过 128 KiB 字符上限 |
| `INVALID_ENVELOPE` | `$` | 既不是三段 `DST1.payload.checksum`，也不是四段 `DST1.1.payload.checksum` |
| `INVALID_CHECKSUM_FORMAT` | `$.checksum` | CRC32 不是 8 位大写十六进制 |
| `INVALID_BASE64URL` | `$.payload` | payload 不是无填充 Base64URL |
| `COMPRESSED_DATA_TOO_LARGE` | `$.payload` | 解码后的压缩数据超过 96 KiB |
| `CHECKSUM_MISMATCH` | `$.checksum` | CRC32 不一致 |
| `DECOMPRESSION_FAILED` | `$.payload` | 不是有效的 zlib 数据 |
| `JSON_TOO_LARGE` | `$.json` | 解压后的 JSON 超过 256 KiB |
| `INVALID_UTF8` | `$.json` | 解压字节不是严格 UTF-8 |

128 KiB 信封上限包含前缀、分隔符和 CRC32。由于 Base64URL 膨胀，任何真正超过 96 KiB 的 payload 也必然先超过当前信封上限；因此清单中的 `compressed-over-limit-precedence` 固定预期为 `INPUT_TOO_LARGE`。`COMPRESSED_DATA_TOO_LARGE` 仍保留为防御性检查。

### 字段错误

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


## 共享向量

`protocol-test-vectors/manifest.json` 是唯一清单，记录 spec 和 android 预期；json 输入校验 JSON，dst1 校验信封并可关联 decodedJson，generator 动态生成边界。valid 为合法、invalid 为非法，future-valid 仅保留未实现能力的差异；不要复制平台私有等价向量。

新增字段同时更新 Schema、共享类型/语义、预编译校验器、合法/非法向量和 Android 独立解析。Web `generate:protocol` 更新生成文件，`verify:protocol` 检查一致性。修改文档不触发旧全套，按最小测试规则执行。

### 确定性生成器


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


可导入的当前样例与生成方式见 [samples](../samples/README.md)，固定日期旧文件只用于兼容解析。

### 体温询问扩展

`{k:8}` 为无额外配置的叶执行类型，适用于整项任务和 STEPS 步骤、日期例外。拒绝未知类型及体温配置中的额外字段；Web 与 Android 活动测试覆盖协议往返、输入范围和步骤约束。DOM 配置备份版本为 DSDOM 1.4，保留旧版本读取。
