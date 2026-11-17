import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.LUMA_PLAYWRIGHT_PORT ?? "3090");
// Each browser project gets its own mock backend and web server, so their data never mixes.
const mobilePort = port + 1;

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
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: {
        baseURL: `http://127.0.0.1:${port}`,
        ...devices["Desktop Chrome"],
        ...(channel ? { channel } : {}),
      },
    },
    {
      name: "mobile",
      use: {
        baseURL: `http://127.0.0.1:${mobilePort}`,
        ...devices["Pixel 7"],
        ...(channel ? { channel } : {}),
      },
    },
  ],
  webServer: [port, mobilePort].map(p => ({
    command: `LUMA_PLAYWRIGHT_PORT=${p} node tests/mock-auth-runner.mjs`,
    url: `http://127.0.0.1:${p}/sign-in`,
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  })),
});
