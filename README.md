# DStationery

DStationery 包含默认的完全离线 Android/Web 产品，以及独立包名和独立存储的联网 Android + 管理员 Web 预发布版本。离线产品不会因联网版而获得网络权限或上传数据。

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
- 联网 Android 包名：`com.ds.localtaskmanager.connected`（debug 另加 `.debug`），只用于 staging 验收和私下分发

首个签名 Release 与此前 Debug APK 的签名不同。已有 Debug 用户必须先导出 DSTB1，卸载 Debug 版，安装 Release，再恢复备份。

## 构建

使用 JDK 17、Android SDK 35 与仓库中的 Gradle Wrapper。联网 Android 只在本机构建：

```text
./gradlew testConnectedDebugUnitTest lintConnectedDebug assembleConnectedDebug
```

离线 Android 由 GitHub `Android CI` 构建、验证并上传 `DStationery-offline-debug` artifact；不要用本机联网产物替代离线 CI 证据。离线/联网 Web 的测试和构建仍由各自 GitHub 工作流负责。

Preview 截图基线固定在 Windows、Temurin `17.0.19+10`、`Asia/Hong_Kong` 与简体中文环境验证；具体命令和模拟器配置见[测试环境说明](docs/android-emulator-testing.md)。

签名 Release 需要先在仓库外配置发布密钥，参见[离线版指南](docs/offline.md#发布与签名)和[联网版指南](docs/connected.md#环境与部署)。

## 文档

- [文档索引与权威来源](docs/README.md)
- [离线版开发与交付指南](docs/offline.md)
- [联网版开发、同步与运维指南](docs/connected.md)
- [版本变更记录](CHANGELOG.md)

## 隐私

离线 DStationery 不申请网络权限，不收集或上传数据。联网预发布版会按空间权限同步任务与执行结果；主题等个人偏好仍只保留本地。完整说明见 [`PRIVACY.md`](PRIVACY.md)，联网版正式发布前还需完成阶段 3 隐私文本门禁。

## 许可与贡献

Copyright (C) 2026 rochelimit_cw。项目以 `GPL-3.0-only` 发布。提交贡献表示同意 [`CONTRIBUTING.md`](CONTRIBUTING.md) 中的 DCO。
