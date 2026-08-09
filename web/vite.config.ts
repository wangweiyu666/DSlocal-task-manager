import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { fileURLToPath } from "node:url";

export default defineConfig({
  base: "./",
  server: {
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] }
  },
  plugins: [
    react(),
    VitePWA({
      registerType: "prompt",
      includeAssets: ["icon.svg"],
      manifest: {
        name: "DStationery Dom",
        short_name: "DS Dom",
        description: "完全离线的 DST1 任务生成器",
        theme_color: "#6750a4",
        background_color: "#fffbfe",
        display: "standalone",
        start_url: "./#/create",
        icons: [
          { src: "icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any maskable" }
        ]
      },
      workbox: {
        navigateFallback: "index.html",
        globPatterns: ["**/*.{js,css,html,svg,json}"]
      }
    })
  ],
  test: {
    environment: "jsdom",
    setupFiles: "./tests/setup.ts",
    include: ["tests/**/*.test.{ts,tsx}"]
  }
});
