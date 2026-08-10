# W32：DStationery 发布加固

## 发布目标

W32 已产出 `0.1.0-alpha.1（2）` 的签名通用 Release APK，并于 2026-08-09 通过 GitHub Releases 预发布；不上传 Google Play。

Release 包名为 `com.ds.localtaskmanager`，Debug 使用 `.debug` 后缀并可并存。首个 Release 不兼容旧 Debug 签名；旧用户必须先导出 DSTB1，卸载 Debug 版，再安装 Release 并恢复。

## 密钥与构建

发布密钥位于仓库外：

- `%USERPROFILE%\.android\local-task-manager-release.jks`
- `%USERPROFILE%\.gradle\local-task-manager-signing.properties`

运行 `scripts/release/setup-release-signing.ps1` 只会在文件不存在时创建 RSA 3072、SHA-256、30 年有效期的 `dstationery-release` 密钥。密钥和密码须分开做加密离线备份。

`prepare-release.ps1` 要求干净提交，执行 JVM、截图、Release Lint、R8 签名构建和最终 APK 审核，并生成 SHA-256 与候选证据。`publish-release.ps1` 只接受与当前 `main` 提交和 APK 哈希一致的 `approved` 证据；设备状态默认必须全部通过，若发布负责人明确授权跳过某项检查，则必须将该项记录为 `waived` 并在 `waivers` 中写明原因，不得伪装为 `passed`。发布前仍要求确认版本标签。

## 安全与隐私

- 最终权限白名单精确包含通知、设备重启、振动，以及 WorkManager 保活所需的网络状态、前台服务、唤醒锁和签名级动态接收器权限；禁止 `INTERNET`、存储、照片、精确闹钟和电池优化豁免权限。
- `allowBackup=false`、`usesCleartextTraffic=false`；只导出启动 Activity。
- FileProvider 只暴露 `cache/shared-images/`；DSTB1 与诊断导出使用系统文件选择器。
- 临时文件最长保留 24 小时；未完成恢复的回滚快照不得按时间误删。
- Release 不加入遥测、崩溃上传、联网更新、自校验或反篡改。
- 诊断事件最多 100 条、保留 7 天，只含模块、错误码、时间和恢复状态。

## 测试与发布证据

- Room schema 固定 v5，覆盖 v1、v2、v3、v4、v5 起点升级且禁止破坏性迁移。
- DST1、DSTB1 覆盖固定恶意样本、确定性随机输入、压缩与大小边界。
- 常用大数据基线：1,000 个任务、10,000 个实例、50,000 条积分/日志。
- API 35 跑全量仪器测试；API 26/33 跑启动、数据库、导航、通知、DST1、DSTB1、分享和设置关键流程。
- Release APK 在三档 API 验证启动、数据库、通知、导入、备份恢复和分享；Android 13+ 真机验证重启、改时区、省电和首次 Debug 数据迁移。
- 时区覆盖 `Asia/Hong_Kong`、`America/New_York`、`UTC` 与 04:00 日界线。

已接受例外：`#818CF8` 配白字不满足 WCAG AA，对此不宣称完整对比度合规。其他控件继续检查 48dp 点击区、TalkBack 顺序、100%/150%/200% 字体和减少动效。

最终发布结果：

- 标签：`v0.1.0-alpha.1`；提交：`70223d675f0695739f24390be953209e00326f41`。
- [GitHub Release](https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.1) 与 [最终发布 CI](https://github.com/wangweiyu666/DSlocal-task-manager/actions/runs/31285405667) 均已完成。
- APK SHA-256：`d885c571ebbef3cc170c19f39db5f2fccc721654edb302aef71a8528ead90fc9`。
- JVM 97/97、截图 18/18、API 35 23/23、API 26 W31 关键流程 3/3 通过；发布证据覆盖 API 26、33、35 与 Android 13+ 真机。
- 已知维护项：发布脚本仍引用旧仓库目标，下一版本发布前按[归档摘要](archive-summary.md#后续维护提醒)修正。

## 开源

项目使用 `GPL-3.0-only`，版权名 `rochelimit_cw`。未来 PR 新提交必须带 DCO `Signed-off-by`。GitHub CI 使用临时 Ubuntu runner，不接触发布密钥。
