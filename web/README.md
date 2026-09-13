# DStationery Web

同源代码提供离线布置 PWA 和联网管理员两种构建，共用任务编辑器、DST1/DST1.1 协议和条件步骤规则。

| 模式 | 开发命令（web 目录） | 构建与输出 | 数据 |
| --- | --- | --- | --- |
| offline | `npm run dev:offline` | `npm run build:offline` → dist-offline | IndexedDB，本地 PWA，不调用云 API |
| connected | `npm run dev:connected` | `npm run build:connected` → dist-connected | 账号/空间、缓存和 outbox；正式环境由 Access Worker 提供，不注册 Service Worker |

先用 Node 22+ 运行 `npm ci`。`build:all` 统一做协议生成一致性、类型检查、双构建和网络边界；发布复用同 SHA 产物，测试集合见 [最小测试指南](../docs/minimal-testing.md)。不要把旧的 dist/ 当作部署目录。

联网本地开发在 cloud 目录安装依赖、复制 `.dev.vars.example` 为忽略的 `.dev.vars` 并填本地占位配置，首次用 `npm run migrate:local` 初始化本地库，再 `npm run dev`；Vite 将 /v1、/health、/__dev 代理至 127.0.0.1:8787。生产登录和部署见 [运行手册](../docs/stage4-production-runbook.md)，本地模拟不证明正式 Access 可用。

离线 Web 的 `.dsdom.json` 是 DSDOM version 1 / minorVersion 2 配置备份，含组、任务、模板、草稿、批次、修订、例外和设置，支持导入/合并；不含 Android 执行答案。清除浏览器数据前导出。Android DSTB1 与云 DSEXPORT 是不同格式。
