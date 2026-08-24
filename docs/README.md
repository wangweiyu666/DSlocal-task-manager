# 文档索引

文档按“产品规则、离线交付、联网交付、机器契约、测试环境”维护。历史 Wxx 和联网阶段快照已经合并，避免同一结论散落在多个文件；旧细节仍可从 Git 历史查阅。

| 需要了解 | 权威来源 |
| --- | --- |
| 产品目标、业务规则和状态语义 | [需求总稿](preview.md) |
| 离线 Android/Web、Room、备份、构建和发布 | [离线版指南](offline.md) |
| 联网架构、同步协议、状态矩阵、环境和恢复 | [联网版指南](connected.md) |
| DST1/DST1.1 字段和严格校验 | [JSON Schema](dst1-schema.json)、[测试向量](dst1-test-vectors.md) |
| DSTB1 文件格式和测试向量 | [DSTB1 格式](dstb1-format.md) |
| Android API 26/33/35 与截图测试 | [模拟器测试环境](android-emulator-testing.md) |

## 构建职责

- 联网 Android：只在开发者本机测试、Lint、构建和签名，不由 GitHub Android CI 上传。
- 离线 Android：推送 `main` 后由 GitHub `Android CI` 构建并上传 APK 与 SHA-256 artifact。
- 离线 Web：由 `Dom Web Pages` 验证并部署 `web/dist-offline`。
- 联网 Web 与 API：由 `Cloud Phase 2` 验证；staging 和 production 均通过受保护的手动工作流部署。

## 配套机器资产

- `protocol-test-vectors/`：Android/Web 共用 DST1 与 DSTB1 向量；
- `cloud-protocol-test-vectors/`：TypeScript/Kotlin 共用联网协议向量；
- `app/schemas/`：离线和联网 Room 导出 Schema；
- `cloud/openapi.yaml` 与 `cloud/schemas/`：云 API 契约；
- `app/src/test/`、`app/src/androidTest/`、`web/tests/`：自动化验证。

## 冲突处理顺序

1. 产品语义以 `preview.md` 为准；
2. 线格式以 Schema、OpenAPI 和测试向量为准；
3. 持久化结构以 migration 与导出的 Schema 为准；
4. 文档与代码不一致时先核实自动化测试和当前实现，再更新主指南。

## 变更规则

| 变更类型 | 必须同步更新 |
| --- | --- |
| 业务语义 | `preview.md`、对应主指南和测试 |
| DST1/DSTB1 | Schema/格式、测试向量、双端解析器测试 |
| Room/D1 | migration、导出 Schema、数据保留或恢复测试 |
| 共享 UI/领域逻辑 | 离线和联网双变体测试 |
| 构建与部署 | 对应主指南和 GitHub 工作流 |

文档中不记录密钥、验证码、令牌、完整私人邮箱、任务正文或可复用的恢复凭据。
