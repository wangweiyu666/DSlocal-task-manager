# 离线开发指南

离线 Android 用 Room 保存任务、执行、历史、积分、提醒和备份；离线 Web 用 IndexedDB 保存任务库、积分组、模板与草稿，通过 DST1/DST1.1 交换任务。无账号、上传、遥测或在线更新检查。产品语义见 [产品规则](preview.md)，字段见 [协议契约](dst1-test-vectors.md)。

## 共享实现规则

Android 共用领域、DAO、导入、执行和界面位于 `app/src/main`，联网专属逻辑在 connected 源集；Web 共用模型和编辑器位于 `web/src`，入口分别为 main.tsx / main-connected.tsx。只有动作或入口不同的界面用能力参数/回调复用，不复制整页。

离线隔离由构建保证，不能只隐藏按钮：Android 排除网络权限及联网客户端、SQLCipher 依赖，Web 排除认证与云 API 代码。Android 使用系统文件选择器，不申请照片或广泛存储权限；共享通知与后台作业可能合入唤醒等普通权限，实际权限以合并 Manifest 和边界检查为准。

## 本地数据

主库 [AppDatabase](../app/src/main/java/com/ds/localtaskmanager/data/AppDatabase.kt) 当前 Room 9，Schema 在 `app/schemas/`。定义与实例分离，步骤/选项 ID 稳定，每日答案独立，完成保留配置与实际得分。8→9 新增通知、选项、条件和得分快照，不自动转换旧任务；联网同步库是独立版本体系。

核心记录包括导入批次、组、定义/例外/实例、步骤、执行进度、信息/心情、备注、流水、日志、结果修订和提醒。迁移连续且不得破坏性重建；变更同时维护迁移、Schema 和必要的数据保留校验。

DST1 不含账号或运行结果，DSTB1 是未加密完整业务备份，规范见 [备份格式](dstb1-format.md)。Web DSDOM 只备份布置端配置，不含 Android 答案；清除网站数据前先导出。

## 构建与交付

Android 离线包名 `com.ds.localtaskmanager`，Debug 另有 `.debug`；联网生产包独立。Web 输出 `web/dist-offline`，相对资源路径、Hash Router、独立 PWA 与缓存；联网输出和 Worker 说明见 [Web 入口](../web/README.md)。

验证入口和同源复用只在 [最小测试指南](minimal-testing.md) 维护；工具见 [Windows/Android 指南](windows-tooling.md)。提交、部署、签名与 APK 审计只在 [生产运行手册](stage4-production-runbook.md) 维护。纯文档或样例检查不要求重跑完整产品套件。
