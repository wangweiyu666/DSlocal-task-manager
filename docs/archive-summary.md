# W00–W32 归档摘要

## 归档结论

Android 实施窗口 W00–W32 已完成，当前可复现基线为 DStationery `0.1.0-alpha.1`（versionCode 2）。本文件用于任务窗口归档和后续恢复上下文；详细产品规则仍以[需求总稿](preview.md)及各机器契约为准。

## 发布基线

| 项目 | 值 |
| --- | --- |
| 发布日期 | 2026-08-09 |
| Git 标签 | `v0.1.0-alpha.1` |
| 发布提交 | `70223d675f0695739f24390be953209e00326f41` |
| Release | [GitHub Releases 预发布](https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.1) |
| APK | [DStationery-0.1.0-alpha.1.apk](https://github.com/wangweiyu666/DSlocal-task-manager/releases/download/v0.1.0-alpha.1/DStationery-0.1.0-alpha.1.apk) |
| APK SHA-256 | `d885c571ebbef3cc170c19f39db5f2fccc721654edb302aef71a8528ead90fc9` |
| 最终发布 CI | [GitHub Actions 31285405667](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/31285405667) |

## 最终验证

- JVM：97/97 通过；Preview 截图：18/18 通过。
- API 35 全量仪器测试：23/23 通过。
- API 26 W31 关键仪器测试：3/3 通过。
- 发布证据另覆盖 API 26、33、35 和 Android 13+ 真机验收。
- `lintRelease`、签名 Release 审核、权限白名单、R8 与资源裁剪通过。
- 未声明或请求网络、照片或广泛存储权限；备份、恢复和诊断导出均使用系统文件选择器。

## 已归档能力

W00–W32 已覆盖 DST1 契约与 Room 迁移、任务执行和重复实例、每日结果与积分修订、延期与重开、本地通知、今日与历史 UI、分享图片、个人统计、独立设置、预测式返回、DSTB1 备份恢复，以及发布、隐私、许可和诊断加固。窗口明细见[实施状态与路线图](android-phase-1-summary-and-roadmap.md)。

## 明确后置或不纳入本版本

- Dom 任务生成网页独立于当前 Android 发布轨道，尚未分配后续窗口。
- 不上传 Google Play；当前发布渠道为 GitHub Releases。
- 不提供账号、云同步、联网更新或遥测。
- 自定义主题颜色、任务日界线调整、Android 端编辑提醒、清除全部数据，以及统计 CSV/自定义区间导出均未纳入当前版本。
- 已接受视觉例外：`#818CF8` 配白字不满足 WCAG AA，不宣称完整对比度合规。

## 后续维护提醒

- `scripts/release/publish-release.ps1` 当前仍引用旧仓库名 `wangweiyu666/DStationery`；下次发布前必须改为 `wangweiyu666/DSlocal-task-manager` 或改为参数化仓库目标。此项不影响已经发布的 `v0.1.0-alpha.1`。
- 新工作应分配新的窗口编号，并同步更新本摘要、路线图、变更记录和版本号。
- 数据结构变更必须继续提供连续 Room migration、导出 Schema 和数据保留测试；线格式不兼容时才升级协议主版本。
