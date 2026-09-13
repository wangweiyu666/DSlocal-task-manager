# 共享协议向量

Android、Web 和 Cloud 共用本目录；manifest.json 是 DST1/DST1.1 的唯一清单，记录规范及 Android 预期。valid 为合法输入，invalid 为非法与错误优先级输入，future-valid 仅用于未实现能力差异；大输入由生成器构造。

规则见 [DST1 契约](../docs/dst1-test-vectors.md) 与 [Schema](../docs/dst1-schema.json)。dstb1/ 是 Android 备份兼容资产，旧版本文件保留用于读取兼容，当前格式见 [DSTB1](../docs/dstb1-format.md)。向量维护不自动触发完整回归，按 [最小测试规则](../docs/minimal-testing.md) 执行。
