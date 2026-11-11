import { defineConfig, devices } from "@playwright/test";

const port = process.env.LUMA_PLAYWRIGHT_PORT ?? "3090";

const isMac = process.platform === "darwin";
const channel = isMac ? "chrome" : undefined;

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        ...(channel ? { channel } : {}),
      },
    },
    {
      name: "mobile",
      use: {
        ...devices["Pixel 7"],
        ...(channel ? { channel } : {}),
      },
    },
  ],
  webServer: {
    command: "node tests/mock-auth-runner.mjs",
    url: `http://127.0.0.1:${port}/sign-in`,
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
});
