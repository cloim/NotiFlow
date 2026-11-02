import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  testMatch: "*.spec.mjs",
  workers: 1,
  timeout: 30000,
  reporter: [
    ["list"],
    [
      "html",
      { open: "never", outputFolder: "../docs/validation/playwright-report" },
    ],
  ],
  outputDir: "../docs/validation/test-results",
  use: {
    baseURL: "http://127.0.0.1:4186",
    headless: true,
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }
      : {},
  },
  webServer: {
    command:
      "node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 4186 --strictPort",
    url: "http://127.0.0.1:4186",
    reuseExistingServer: false,
  },
});
