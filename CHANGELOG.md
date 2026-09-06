# Changelog

## [Unreleased]

### Added

- 新增心情记录执行类型：Android 五档吸附滑动条、自动保存草稿、可选感受和历史查看；离线／联网 Web 创建及管理员结果展示同步支持。Room 升级至 7，DSTB1 业务载荷升级至 3 并兼容旧备份。
- 联网阶段 3：版本化隐私首次门禁、最近邮箱验证保护的 DSEXPORT v1 角色限定导出，以及 30 天恢复期/立即永久删除。
- 独立 35 天删除账本与灾备恢复重放、结构化日志白名单、Cloudflare/Resend 用量预警与保护模式。
- 管理员 Web 和执行者 Android 的导出、删除、恢复与服务保护状态交互。
- Windows 开发调用统一优先使用 PowerShell 7 和用户 PATH。
- 联网阶段 4：新增独立生产执行者 Android 包名、沙箱与签名，受审计的公开 GitHub prerelease 流程。
- 新增 staging D1 Time Travel/删除账本恢复演练、production 双确认门禁、隐藏管理端配置与零付费应用层单邮箱验证。
- 明确三个账号真实试运行和连续七天生产观察标准。
- DST1.1 重复任务单日修改、撤销和恢复，Dom Web 与 Sub Android 同步支持。
- Dom 使用 DSDOM v1.2 保存单日例外；Sub 使用 Room v1.6（内部 Schema 6）和 DSTB1 业务 schema v2 持久化。

### Changed

- Android 开发版本更新为 `0.1.0-alpha.4`（versionCode 5），Web 更新为 `0.2.0`。

## [Unreleased]

尚无已记录变更。

## [0.1.0-alpha.3] - 2026-08-09

### Added

- 今日结果分享预览支持“全部任务 / 仅未完成”筛选、双指缩放和双击放大。

### Changed

- 今日结果图改为连续成绩单版式，优先展示必做完成数，并以文字、图标和语义色共同表达状态。
- 信息告知图改为任务标题优先的信笺版式，完整保留长正文。
- 分享图不足一屏时在预览区域上下视觉居中；发送为主操作，保存为次操作。

## [0.1.0-alpha.2] - 2026-08-09

### Added

- W33 新增“靛紫”和“晴空”两种内置 UI 配色，可在设置页即时切换。
- 新增离线 Dom 任务生成网页及 GitHub Pages 发布流程。

### Changed

- 分享图片跟随当前 UI 配色；错误色调整为更柔和的覆盆子红，并减少非错误场景中的红色使用。
- 修正 Dom PWA 页面语言声明和应用内旧仓库链接。

## [0.1.0-alpha.1] - 2026-08-09

首个公开签名预发布版本。此前 Debug 用户需通过 DSTB1 完成首次数据迁移。

### Added

- DST1 严格校验、预览、原子导入与完整任务执行流程。
- 今日、历史、每日结果、分享图片、“我的”统计和独立设置页面。
- 本地提醒、预测式返回、DSTB1 备份与合并/替换恢复。
- 诊断导出、隐私与许可页面、DStationery 品牌和 Android 自适应图标。
- 签名 Release 流程、跨平台 CI、发布审核与校验文件。

### Changed

- Debug 与 Release 使用独立包名。
- Release 启用 R8、混淆与资源裁剪。

### Known issues

- `#818CF8` 主色按钮上的白色文字未达到 WCAG AA 对比度，作为已确认视觉例外保留。

[0.1.0-alpha.1]: https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.1
[0.1.0-alpha.2]: https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.3]: https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.3
