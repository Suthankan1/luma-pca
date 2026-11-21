import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";
import { readFile } from "node:fs/promises";
import { longDayLabel } from "../../src/lib/staff-home";

async function signIn(page: Page, email = "student@example.invalid") {
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
}

async function liveState(page: Page, ended: boolean, online = 12) {
  const response = await page.request.put(mockApiBase() + "/__test__/student-live", {
    headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" }, data: { ended, online },
  });
  expect(response.status()).toBe(204);
}

test("Students open their report and select scheduled classes on a monthly calendar", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("heading", { name: "My Report" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  const navigation = page.getByRole("navigation", { name: "Student portal" });
  await expect(navigation.getByRole("link", { name: "My Report" })).toHaveAttribute("aria-current", "page");
  if (page.viewportSize()!.width <= 760) {
    await expect(navigation).toHaveCSS("position", "fixed");
    const before = await navigation.boundingBox();
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    expect(await navigation.boundingBox()).toEqual(before);
    await navigation.getByRole("link", { name: "Calendar" }).click();
    await expect(navigation.getByRole("link", { name: "Calendar" })).toHaveAttribute("aria-current", "page");
  } else {
    await expect(navigation).not.toHaveCSS("position", "fixed");
  }
  await expect(page.getByRole("link", { name: "My classes" })).toHaveCount(0);
  const date = new Date(Date.now() + 3 * 86400_000).toISOString().slice(0, 10);
  await page.goto(`/student/classes?month=${date.slice(0, 7)}`);
  await expect(page).toHaveURL(/\/student\/calendar/);
  await expect(page.getByRole("heading", { name: "Calendar", exact: true })).toBeVisible();
  const calendar = page.getByRole("region", { name: "Class calendar" });
  await calendar.getByRole("button", { name: new RegExp(longDayLabel(date)) }).click();
  await expect(page.getByText("Upcoming Zoom webinar | MON", { exact: true })).toBeVisible();
  await expect(page.getByText("Webinar title suffix")).toHaveCount(0);
  await expect(page.locator('a[href*="zoom.us"]')).toHaveCount(0);
  await calendar.getByRole("link", { name: "Next month" }).click();
  await expect(page.getByText("Upcoming Zoom webinar")).toHaveCount(0);
  await calendar.getByRole("link", { name: "Go to today" }).click();
  await expect(page).toHaveURL(/\/student\/calendar$/);
});

test("Live Now updates its plotted counts and hides them when the class ends", async ({ page }) => {
  await page.clock.install();
  await liveState(page, false);
  try {
    await signIn(page);
    await page.getByRole("link", { name: "Live Now" }).click();
    await expect(page.getByRole("heading", { name: "Live class now" })).toBeVisible();
    await expect(page.locator('a[href*="zoom.us"]')).toHaveCount(0);
    const chart = page.getByRole("slider", { name: "Explore online counts over time" });
    await expect(chart).toHaveAttribute("aria-valuetext", /12 online/);
    await chart.press("Home");
    await expect(chart).toHaveAttribute("aria-valuetext", /4 online/);
    await liveState(page, false, 14);
    await page.clock.fastForward(31_000);
    await expect(page.locator(".student-live-metrics")).toContainText("Online now14");

    await liveState(page, true);
    await page.clock.fastForward(31_000);
    await expect(page.getByRole("heading", { name: "No live class" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Previous class" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Next class" })).toBeVisible();
    const countdown = page.getByRole("timer", { name: "Time until the next scheduled class" });
    await expect(countdown).toBeVisible();
    const before = await countdown.textContent();
    await page.clock.fastForward(2_000);
    await expect(countdown).not.toHaveText(before ?? "");
    await expect(chart).toHaveCount(0);
    await expect(page.locator(".student-live-metrics")).toHaveCount(0);
    const rail = page.getByRole("complementary", { name: "Academy navigation" });
    await rail.getByRole("button", { name: "Collapse sidebar" }).click();
    await expect(rail.getByRole("link", { name: "LUMA PCA Attendance System" })).toBeVisible();
    await page.getByRole("button", { name: "Pause", exact: true }).click();
    await expect(page.getByText("Auto-refresh paused")).toBeVisible();
    await liveState(page, false, 18);
    await page.getByRole("button", { name: "Refresh now" }).click();
    await expect(page.locator(".student-live-metrics")).toContainText("Online now18");
  } finally {
    await liveState(page, false);
  }
});

test("A student without enrolments sees an empty calendar and no live class", async ({ page }) => {
  await signIn(page, "new-student@example.invalid");
  await page.getByRole("link", { name: "Calendar" }).click();
  await expect(page.getByText("No classes on this day.")).toBeVisible();
  await page.getByRole("link", { name: "Live Now" }).click();
  await expect(page.getByRole("heading", { name: "No live class" })).toBeVisible();
});

test("My Report downloads the selected month's private PDF with no appeals or batches panel", async ({ page }) => {
  const path = "/student/attendance/download?month=2026-11";
  expect((await page.request.get(path)).status()).toBe(401);
  await signIn(page);
  await page.goto("/student?month=2026-11");
  await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  await expect(page.getByText("Attendance appeals", { exact: true })).toHaveCount(0);
  await expect(page.getByText("My batches", { exact: true })).toHaveCount(0);
  const ready = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download report" }).click();
  const download = await ready;
  expect(download.suggestedFilename()).toBe("LUMA-attendance-2026-11.pdf");
  const bytes = await readFile(await download.path());
  expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
  expect(bytes.length).toBeLessThan(180_000);
  const response = await page.request.get(path);
  expect(response.headers()["cache-control"]).toBe("private, no-store");
  expect((await page.request.get("/student/attendance/download?month=invalid")).status()).toBe(400);
});

test("The report shell streams while attendance is slow and a warmed Calendar opens from memory", async ({ page }) => {
  async function holdReport(mode: "hold" | "release") {
    const response = await page.request.put(mockApiBase() + "/__test__/student-overview-response", {
      headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" }, data: { mode },
    });
    expect(response.status()).toBe(204);
  }

  await holdReport("hold");
  try {
    await signIn(page);
    await expect(page.getByRole("heading", { name: "My Report" })).toBeVisible();
    await expect(page.getByRole("status", { name: "Loading student profile attendance" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Class history" })).toHaveCount(0);
  } finally {
    await holdReport("release");
  }
  await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  const nav = page.getByRole("navigation", { name: "Student portal" });
  const calendar = nav.getByRole("link", { name: "Calendar" });
  await calendar.click();
  await expect(page.getByRole("region", { name: "Class calendar" })).toBeVisible();
  await nav.getByRole("link", { name: "My Report" }).click();
  await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  await page.waitForLoadState("networkidle");

  const requests: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname === "/student/calendar" && request.headers().rsc === "1") requests.push(request.url());
  });
  await calendar.click();
  await expect(page.getByRole("region", { name: "Class calendar" })).toBeVisible();
  expect(requests).toHaveLength(0);
});
