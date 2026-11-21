import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";
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
  await expect(page.getByRole("link", { name: "My classes" })).toHaveCount(0);
  const date = new Date(Date.now() + 3 * 86400_000).toISOString().slice(0, 10);
  await page.goto(`/student/classes?month=${date.slice(0, 7)}`);
  await expect(page).toHaveURL(/\/student\/calendar/);
  await expect(page.getByRole("heading", { name: "Calendar", exact: true })).toBeVisible();
  const calendar = page.getByRole("region", { name: "Class calendar" });
  await calendar.getByRole("button", { name: new RegExp(longDayLabel(date)) }).click();
  await expect(page.getByText("Upcoming Zoom webinar")).toBeVisible();
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
    await expect(chart).toHaveCount(0);
    await expect(page.locator(".student-live-metrics")).toHaveCount(0);
    const rail = page.getByRole("complementary", { name: "Academy navigation" });
    await rail.getByRole("button", { name: "Collapse sidebar" }).click();
    await expect(rail.getByRole("link", { name: "LUMA PCA Attendance System" })).toBeVisible();
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
