# DStationery

DStationery 是一款完全离线的 Android 本地任务、执行、积分、历史与备份应用。

## 当前版本

- 预发布版本：`0.1.0-alpha.1`（versionCode 2）
- 最低系统：Android 8.0 / API 26
- 包名：`com.ds.localtaskmanager`
- 权限：通知、设备重启、振动；无网络、照片或广泛存储权限

首个签名 Release 与此前 Debug APK 的签名不同。已有 Debug 用户必须先导出 DSTB1，卸载 Debug 版，安装 Release，再恢复备份。

## 构建

使用 JDK 17、Android SDK 35 与仓库中的 Gradle Wrapper：

```text
./gradlew testDebugUnitTest validateDebugScreenshotTest lintRelease assembleDebug
```

签名 Release 需要先在仓库外配置发布密钥，参见 [`docs/w32-release-hardening.md`](docs/w32-release-hardening.md)。

## 隐私

DStationery 不申请网络权限，不收集或上传数据。完整说明见 [`PRIVACY.md`](PRIVACY.md)。

## 许可与贡献

Copyright (C) 2026 rochelimit_cw。项目以 `GPL-3.0-only` 发布。提交贡献表示同意 [`CONTRIBUTING.md`](CONTRIBUTING.md) 中的 DCO。
