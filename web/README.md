# DStationery Dom Web

完全离线的 DST1 / DST1.1 任务生成 PWA，与仓库中的 Android Sub 共用 `docs/dst1-schema.json` 和 `protocol-test-vectors/`。当前 Web 版本为 `0.2.0`。

```text
npm install
npm run dev
npm test
npm run build
```

生产构建位于 `dist/`，使用 Hash Router 和相对资源路径，可直接部署到 GitHub Pages 或任意静态服务器。业务数据仅保存在浏览器 IndexedDB；清除网站数据前应从设置页导出 `.dsdom.json` 配置。
