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
    ...(!connected ? [VitePWA({
      registerType: "prompt",
      includeAssets: ["icon.svg"],
      manifest: {
        id: "dstationery-dom-offline",
        name: "DStationery Dom",
        short_name: "DS Dom",
        description: "完全离线的 DST1 任务生成器",
        lang: "zh-CN",
        theme_color: "#6750a4",
        background_color: "#fffbfe",
        display: "standalone",
        start_url: "./#/create",
        icons: [
          { src: "icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any maskable" }
        ]
      },
      workbox: {
        cacheId: "dstationery-offline",
        navigateFallback: "index.html",
        globPatterns: ["**/*.{js,css,html,svg,json}"]
      }
    })] : [])
  ],
  test: {
    environment: "jsdom",
    setupFiles: "./tests/setup.ts",
    include: ["tests/**/*.test.{ts,tsx}"]
  }
  };
});
