# 日期与分步骤验证记录

日期：2026-09-06

这份记录汇总当前已执行的 Web、Cloud 和 Android 验证证据。Android 验证使用当前工作树（基于 c67dbd72823e5ccd99d31e689d2aa8a954c2e5f0 的未提交变更，未创建新提交）；Cloud 代码本次未部署。

## Web

在仓库根目录执行：

```text
npm --prefix web test
npm --prefix web run build:all
git diff --check -- web shared docs
```

结果：

- Web 测试：10 个测试文件、78 个测试通过，失败 0，跳过 0。
- `build:all`：协议生成校验、TypeScript typecheck、offline/connected Vite 构建和 bundle boundary 检查全部通过。
- `git diff --check`：通过。

冻结后的稳定 ID 删除回归再次执行：

```text
npm --prefix web test -- --run tests/mood-task-editor.test.tsx tests/task-logic.test.ts
npm --prefix web run typecheck
```

结果：2 个测试文件、15 个测试通过；typecheck 通过。

覆盖内容包括 DST1/DST1.1 校验、STEPS 例外继承与切出、稳定步骤 ID、结果快照名称、模板与备份、预览跨午夜冻结、IndexedDB 提交回滚重试，以及任务/例外编辑器。

预览和日期语义见 [preview.md](preview.md) 与 [date-steps-implementation-plan.md](date-steps-implementation-plan.md)。备份 v4 的整组步骤恢复和 Room 8 不转换旧定义约定见 [dstb1-format.md](dstb1-format.md)。

## Cloud

Cloud 最近一次已执行验证：

```text
npm --prefix cloud run typecheck
npm --prefix cloud test
```

结果：typecheck 通过；7 个测试文件、36 个测试通过，失败 0，跳过 0。Cloud 在该验证后未继续修改，因此本次 Web-only 验证没有重复运行 Cloud 套件。

步骤 Cloud 变更另完成了一次隔离本地 Worker smoke：使用专用 `.wrangler/steps-smoke` 持久化目录，将主库和删除账本的全部本地 migrations（包含 `0007_atomic_sync_commands.sql`）应用到同一状态，migration 版本为 7；随后用该状态启动本地 Worker 并运行 `cloud/scripts/smoke-local.mjs`。结果：本地 HTTP smoke 通过，测试后已停止该 Worker；未连接 production 或 staging。

## 浏览器人工验收

主代理已在 390×844 视口验收：步骤卡片无横向溢出；计时/计数步骤的顺序、参数和重排保持；保存后日期回填；任务库恢复已保存的 timer 10 与 counter 3 定义后参数保持一致。该证据属于人工浏览器检查，不替代自动化测试。

## Android

执行环境：Windows、JDK 17、Android SDK；Gradle 使用用户缓存目录 `$env:USERPROFILE/.gradle`，所有 Gradle 验证均使用 `--no-daemon --offline`。首次普通沙箱运行因 Robolectric `MavenArtifactFetcher` 的缓存访问 `SocketException` 导致 83 个统一环境失败；随后使用相同用户缓存和提升的本地访问重跑，未修改断言或跳过测试。

执行的 Gradle 任务与参数如下；`gradle.bat` 指向本机已发现的缓存 Gradle，省略当前进程 PATH 设置，发现方式见 [Windows 工具指南](windows-tooling.md)。

```text
gradle.bat :app:testConnectedDebugUnitTest --no-daemon --offline --stacktrace
gradle.bat :app:testConnectedDebugUnitTest --tests com.ds.localtaskmanager.data.StepsImportRegressionTest --no-daemon --offline
gradle.bat :app:assembleOfflineDebug :app:assembleConnectedDebug :app:assembleProductionDebug :app:lintOfflineRelease :app:lintConnectedDebug :app:lintProductionDebug --no-daemon --offline
bash scripts/ci/check-connectivity-boundaries.sh
gradle.bat :app:compileOfflineDebugAndroidTestKotlin --no-daemon --offline
```

结果：联网 Debug 单元测试 38 个 XML 类、158/158 通过，失败 0、错误 0、跳过 0。完整 XML 与 HTML 报告保存在 `app/build/reports/mood/full-connected-158-20260906/`；最新 Import focused 测试 5/5 通过。三 Debug 构建、三 lint、offline connectivity boundary 和 `compileOfflineDebugAndroidTestKotlin` 均成功。首次 AndroidTest 编译发现 `StepsSectionTest` 使用不存在的 `assertDoesNotExist` 扩展，UI 测试 owner 修正后重新编译通过。

APK 产物（均为 Debug，包名分别为 `com.ds.localtaskmanager.debug`、`com.ds.localtaskmanager.connected.debug`、`com.ds.localtaskmanager.connected.production.debug`）：

| 环境 | 绝对路径 | SHA-256 |
| --- | --- | --- |
| offline（无 API） | `D:/Projects/local-task-manager/app/build/outputs/apk/offline/debug/app-offline-debug.apk` | `7177039215D34D399AE3E25F41C1755FF60819A1D34700D6355FBAF99D3580E4` |
| connected（staging，`api-staging.rochelimit.me`） | `D:/Projects/local-task-manager/app/build/outputs/apk/connected/debug/app-connected-debug.apk` | `2F7C40656DC4988A412D83FE88C9E25D9C5B41E99569CDFE6C19465519166B6D` |
| production（`api.rochelimit.me`） | `D:/Projects/local-task-manager/app/build/outputs/apk/production/debug/app-production-debug.apk` | `32D68F8F484A876964E713EF85BB17B9DEDBD8F2D7E7C1367251DCE4A39FF513` |

当前没有连接设备或可用 AVD，因此未执行 Android 设备上的安装、视觉、读屏、键盘/IME 或真实账号登录/离线同步验收。Cloud 生产环境本次也未部署；联网 APK 的 production API 配置不代表服务端步骤能力已发布。

## 待验证

- Android 设备视觉、读屏、IME、真实账号登录和离线同步：当前无设备/AVD，未执行。
- Cloud production 步骤能力部署与三端联调：本次未部署 Cloud production；本地 HTTP smoke 已在上文单独记录。
