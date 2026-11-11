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
  timeout: 90_000,
  use: {
    baseURL: process.env.LUMA_REAL_STACK_URL,
    trace: "retain-on-failure",
    ...devices["Desktop Chrome"],
    ...(process.platform === "darwin" ? { channel: "chrome" } : {}),
  },
});
