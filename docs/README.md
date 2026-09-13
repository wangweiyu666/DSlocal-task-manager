# 文档索引

每项规则只在一处维护；历史记录不能替代当前验证或触发重复测试。

| 内容 | 主文档 |
| --- | --- |
| 产品语义、日期、任务与积分 | [产品规则](preview.md) |
| 离线实现与数据边界 | [离线指南](offline.md) |
| 联网协议、同步与权限 | [联网指南](connected.md) |
| 提交、推送、部署、发布与环境 | [生产运行手册](stage4-production-runbook.md) |
| 最小测试、Android 清单、复用及故障诊断 | [测试指南](minimal-testing.md) |
| PATH、缓存、模拟器与截图 | [工具指南](windows-tooling.md) |
| 历史验证与交付证据 | [验证记录](verification-records.md)；[用例审查](android-test-audit-2026-09-09.md) |
| DST1 / DSTB1 契约 | [Schema](dst1-schema.json)、[DST1 向量](dst1-test-vectors.md)、[备份格式](dstb1-format.md) |
| 可导入合成任务、当前协议样例 | [样例与生成命令](../samples/README.md) |
| 图标来源与许可 | [心情图标](mood-artwork.md) |

机器契约还包括 `cloud/openapi.yaml`、`cloud/schemas/`、`app/schemas/` 和两个 protocol-test-vectors 目录。协议与数据结构变更同步更新对应契约、实现及必要测试；产品规则更新需求稿，流程变更更新测试或运行手册，不在索引复制细节。

`android-test-profiles.md` 与 `production-only.md` 仅保留旧技能路径兼容。旧日期/条件步骤实施计划已合并到产品与协议，后台专项已合并到联网指南；实际结果以验证记录为准。文档不记录密钥、验证码、会话、私人邮箱、任务正文或可复用恢复凭据。
