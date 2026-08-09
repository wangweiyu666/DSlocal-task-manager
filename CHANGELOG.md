# Changelog

## [Unreleased]

### Added

- W33 新增“靛紫”和“晴空”两种内置 UI 配色，可在设置页即时切换。

### Changed

- 分享图片跟随当前 UI 配色；错误色调整为更柔和的覆盆子红，并减少非错误场景中的红色使用。

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
