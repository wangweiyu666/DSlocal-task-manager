import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-connected",
  fullyParallel: false,
  webServer: {
    command: "node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 4174 --outDir dist-connected",
    port: 4174,
    reuseExistingServer: false,
  },
  use: {
    baseURL: "http://127.0.0.1:4174",
    ...devices["Desktop Chrome"],
  },
});
