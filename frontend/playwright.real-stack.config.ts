import { defineConfig, devices } from "@playwright/test";

/**
 * Drives the real stack (PostgreSQL, Spring backend, Next.js production server)
 * started by scripts/staff-ui-real-stack.sh. Not part of `npm run test:e2e`, which
 * uses the mock backend.
 */
export default defineConfig({
  testDir: "./tests/real-stack",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  forbidOnly: !!process.env.CI,
  timeout: 90_000,
  use: {
    baseURL: process.env.LUMA_REAL_STACK_URL,
    trace: "retain-on-failure",
    ...devices["Desktop Chrome"],
    // Installed Google Chrome: present on macOS developer machines and preinstalled on
    // GitHub's Ubuntu runners, so no browser download or apt-get is needed.
    ...(process.platform === "darwin" || process.env.CI ? { channel: "chrome" } : {}),
  },
});
