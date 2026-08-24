# DStationery

DStationery 包含默认的完全离线 Android/Web 产品，以及独立包名和独立存储的联网执行者 Android + 管理员 Web。离线产品不会因联网版而获得网络权限或上传数据。

## 下载

- [查看当前 Release](https://github.com/wangweiyu666/DSlocal-task-manager/releases/tag/v0.1.0-alpha.3)
- [下载 DStationery 0.1.0-alpha.3 APK](https://github.com/wangweiyu666/DSlocal-task-manager/releases/download/v0.1.0-alpha.3/DStationery-0.1.0-alpha.3.apk)
- [下载 SHA-256 校验文件](https://github.com/wangweiyu666/DSlocal-task-manager/releases/download/v0.1.0-alpha.3/DStationery-0.1.0-alpha.3.apk.sha256)

## 当前版本

- 当前已发布预览版：`0.1.0-alpha.3`（versionCode 4）
- 当前离线开发版：Android `0.1.0-alpha.6`（versionCode 7），Web `0.2.0`
- 最低系统：Android 8.0 / API 26
- 包名：`com.ds.localtaskmanager`
- 离线 Android 权限：通知、设备重启、振动；无网络、照片或广泛存储权限
- 当前联网开发版：Android `0.1.0-alpha.9`（versionCode 10）
- staging Android 包名：`com.ds.localtaskmanager.connected`（debug 另加 `.debug`）
- 生产执行者 Android：`0.1.0-alpha.10-executor`（versionCode 11），包名 `com.ds.localtaskmanager.connected.production`
- 生产执行者 APK 通过公开 GitHub Releases 分发并附 SHA-256；管理员网址不在仓库或 Release 中公布

首个签名 Release 与此前 Debug APK 的签名不同。已有 Debug 用户必须先导出 DSTB1，卸载 Debug 版，安装 Release，再恢复备份。

## 构建

使用 JDK 17、Android SDK 35 与仓库中的 Gradle Wrapper。staging 与生产执行者 Android 可分别验证：

```text
./gradlew testConnectedDebugUnitTest lintConnectedDebug assembleConnectedDebug
./gradlew testProductionDebugUnitTest lintProductionRelease assembleProductionRelease
```

`Android CI` 构建并验证离线、staging 和 production 变体，但只上传离线 Debug artifact。生产执行者 APK 使用仓库外的独立密钥在本地完成签名审计，再由受确认脚本上传公开 GitHub Release。

Preview 截图基线固定在 Windows、Temurin `17.0.19+10`、`Asia/Hong_Kong` 与简体中文环境验证；具体命令和模拟器配置见[测试环境说明](docs/android-emulator-testing.md)。

签名 Release 需要先在仓库外配置发布密钥，参见[离线版指南](docs/offline.md#发布与签名)和[联网版指南](docs/connected.md#环境与部署)。

## 文档

- [文档索引与权威来源](docs/README.md)
- [离线版开发与交付指南](docs/offline.md)
- [联网版开发、同步与运维指南](docs/connected.md)
- [阶段四生产运行手册](docs/stage4-production-runbook.md)
- [版本变更记录](CHANGELOG.md)

## 隐私

离线 DStationery 不申请网络权限，不收集或上传数据。联网预发布版会按空间权限同步任务与执行结果；主题等个人偏好仍只保留本地。联网版首次进入业务数据前必须确认版本化隐私说明，并提供角色限定导出及 30 天/立即删除。完整说明见 [`PRIVACY.md`](PRIVACY.md)。

## 许可与贡献

Copyright (C) 2026 rochelimit_cw。项目以 `GPL-3.0-only` 发布。提交贡献表示同意 [`CONTRIBUTING.md`](CONTRIBUTING.md) 中的 DCO。
