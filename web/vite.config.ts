import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { fileURLToPath } from "node:url";

export default defineConfig(({ mode }) => {
  const connected = mode === "connected";
  return {
  base: connected ? "/" : "./",
  build: {
    outDir: connected ? "dist-connected" : "dist-offline",
    emptyOutDir: true
  },
  define: {
    __BUILD_CHANNEL__: JSON.stringify(connected ? "connected" : "offline")
  },
  server: {
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    ...(connected ? { proxy: { "/v1": "http://127.0.0.1:8787", "/health": "http://127.0.0.1:8787", "/__dev": "http://127.0.0.1:8787" } } : {})
  },
  plugins: [
    {
      name: "select-connectivity-entry",
      transformIndexHtml: {
        order: "pre",
        handler(html) {
          if (!connected) return html;
          return html
            .replace("/src/main.tsx", "/src/main-connected.tsx")
            .replace("DStationery Dom 离线任务生成器", "DStationery 联网版基础壳")
            .replace("<title>DStationery Dom</title>", "<title>DStationery 联网版</title>");
        }
      }
    },
    react(),
    VitePWA({
      registerType: connected ? "autoUpdate" : "prompt",
      includeAssets: ["icon.svg"],
      manifest: {
        id: connected ? "dstationery-connected" : "dstationery-dom-offline",
        name: connected ? "DStationery 联网版" : "DStationery Dom",
        short_name: connected ? "DS 联网" : "DS Dom",
        description: connected ? "DStationery 联网版基础壳" : "完全离线的 DST1 任务生成器",
        lang: "zh-CN",
        theme_color: "#6750a4",
        background_color: "#fffbfe",
        display: "standalone",
        start_url: connected ? "./" : "./#/create",
        icons: [
          { src: "icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any maskable" }
        ]
      },
      workbox: {
        cacheId: connected ? "dstationery-connected" : "dstationery-offline",
        navigateFallback: "index.html",
        globPatterns: ["**/*.{js,css,html,svg,json}"],
        ...(connected ? { clientsClaim: true, skipWaiting: true } : {})
      }
    })
  ],
  test: {
    environment: "jsdom",
    setupFiles: "./tests/setup.ts",
    include: ["tests/**/*.test.{ts,tsx}"]
  }
  };
});
