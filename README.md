# DStationery

DStationery 包含默认的完全离线 Android/Web 产品，以及独立包名和独立存储的联网执行者 Android + 管理员 Web。离线产品不会因联网版而获得网络权限或上传数据。

## 下载与版本

[GitHub Releases](https://github.com/wangweiyu666/DSlocal-task-manager/releases) 提供 APK 与 SHA-256；具体版本以发布页面和 `app/build.gradle.kts` 为准，避免在多份文档重复更新版本号。

最低 Android 8.0 / API 26。离线包名 `com.ds.localtaskmanager`，生产执行者 `com.ds.localtaskmanager.connected.production`；只保留 offline / production，环境规则见 [生产运行手册](docs/stage4-production-runbook.md#环境与兼容)。

首个签名 Release 与此前 Debug APK 的签名不同。已有 Debug 用户必须先导出 DSTB1，卸载 Debug 版，安装 Release，再恢复备份。

## 开发与交付

使用 JDK 17、Android SDK 35 和仓库 Gradle Wrapper。测试与同源结果复用见 [最小测试指南](docs/minimal-testing.md)，构建、截图和模拟器见 [工具指南](docs/windows-tooling.md)，签名与正式部署见 [生产运行手册](docs/stage4-production-runbook.md)。

更多产品、协议和历史证据见 [文档索引](docs/README.md)，版本变化见 [CHANGELOG](CHANGELOG.md)。

## 隐私

离线 DStationery 不申请网络权限，不收集或上传数据。联网预发布版会按空间权限同步任务与执行结果；主题等个人偏好仍只保留本地。联网版首次进入业务数据前必须确认版本化隐私说明，并提供角色限定导出及 30 天/立即删除。完整说明见 [`PRIVACY.md`](PRIVACY.md)。

## 许可与贡献

Copyright (C) 2026 rochelimit_cw。项目以 `GPL-3.0-only` 发布。提交贡献表示同意 [`CONTRIBUTING.md`](CONTRIBUTING.md) 中的 DCO。
