import { test, expect, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

/** The mock API, as the signed-in staff member (the mock's fixed token). */
async function api(page: Page, method: "GET" | "POST", path: string, data?: unknown) {
  const response = await page.request.fetch(mockApiBase() + path, {
    method, data, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test", "Content-Type": "application/json" },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}

const colomboDate = (ms: number) => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date(ms));

test.describe("Staff home", () => {
  test("Managers see the day's classes, move between days, the month calendar and live numbers; other roles see their own", async ({ page }) => {
    const p = test.info().project.name.toUpperCase();
    await signInStaff(page, "admin-enrolled@example.invalid");
    await expect(page.getByRole("link", { name: "Home", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("region", { name: /^Classes on Today/ })).toBeVisible();
    await expect(page.getByRole("region", { name: "Needs attention" })).toBeVisible();

    // A long-titled class later today and one tomorrow.
    const course = await api(page, "POST", "/api/v1/courses", { code: `HOME-${p}`, title: "Home page course" });
    const batch = await api(page, "POST", "/api/v1/batches", { courseId: course.id, code: "A", name: "Batch A", startsOn: "2026-01-01" });
    const long = `Model Paper Class ${p} - November | 2027 Batch & Repeaters | by Eng. Mithukshan Sivakumar | Professional Physics Educator`;
    const later = Date.now() + 2 * 60_000;
    for (const [title, start] of [[long, later], [`Theory ${p}`, later + 24 * 3600_000]] as const) {
      await api(page, "POST", "/api/v1/class-sessions", { title, startsAt: new Date(start).toISOString(),
        endsAt: new Date(start + 3600_000).toISOString(), timeZone: "Asia/Colombo", batchIds: [batch.id], zoomWebinarId: null });
    }
    await page.reload();
    const day = page.getByRole("region", { name: /^Classes on Today/ });
    const row = day.getByRole("link", { name: new RegExp(long.replace(/[|]/g, "\\|")) });
    await expect(row).toBeVisible();
    // One line; on a pointer device the full title shows on hover, and Escape hides it again.
    const box = await row.locator(".one-line-text").boundingBox();
    expect(box!.height).toBeLessThan(30);
    if (test.info().project.name === "chromium") {
      await row.hover();
      await expect(row.locator(".one-line-full")).toBeVisible();
      await expect(row.locator(".one-line-full")).toHaveText(long);
      await page.keyboard.press("Escape");
      await expect(row.locator(".one-line-full")).toBeHidden();
    }

    // The top card shows what is live, or the next class when nothing is (another test may have started a live webinar).
    await expect(page.getByRole("region", { name: "Live now" }).or(page.getByText("No class is live right now"))).toBeVisible();

    // Next and previous day.
    await day.getByRole("link", { name: "Next day" }).click();
    await expect(page).toHaveURL(new RegExp(`day=${colomboDate(later + 24 * 3600_000)}`));
    const tomorrow = page.getByRole("region", { name: /^Classes on Tomorrow/ });
    await expect(tomorrow.getByRole("link", { name: new RegExp(`Theory ${p}`) })).toBeVisible();
    await tomorrow.getByRole("link", { name: "Today" }).click();
    await expect(page).toHaveURL(/\/staff$/);

    // The month calendar marks today and its classes; choosing a day shows it in the list.
    const calendar = page.getByRole("region", { name: /^[A-Z][a-z]+ \d{4}$/ });
    await expect(calendar.getByRole("link", { name: /, today: \d+ class/ })).toBeVisible();
    await calendar.getByRole("link", { name: new RegExp(`^${new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", weekday: "long", day: "numeric", month: "long", year: "numeric" })
      .format(new Date(later + 24 * 3600_000)).replace(",", "")}`) }).click();
    await expect(page.getByRole("region", { name: /^Classes on Tomorrow/ })).toBeVisible();
    await calendar.getByRole("link", { name: "Next month" }).click();
    await expect(page).toHaveURL(/month=\d{4}-\d{2}/);
    await page.getByRole("link", { name: "This month" }).click();

    // A webinar Zoom reports live: its numbers replace the next-class card.
    await api(page, "POST", "/api/v1/zoom/monitors", { webinar: "84400000001" });
    await page.goto("/staff");
    const live = page.getByRole("region", { name: "Live now" });
    const card = live.getByRole("article", { name: "Live: A/L Physics 2027" });
    await expect(card).toBeVisible();
    await expect(card.getByRole("definition").nth(2)).toHaveText("12");
    await expect(live.getByRole("button", { name: "Pause" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
    await page.getByRole("link", { name: "Attendance reports" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard$/);

    await signInStaff(page, "instructor@example.invalid");
    await expect(page.getByRole("region", { name: /^Classes on Today/ })).toBeVisible();
    await expect(page.getByRole("region", { name: "Needs attention" })).toHaveCount(0);

    await signInStaff(page, "analyst@example.invalid");
    await page.getByRole("region", { name: "Attendance by batch" }).getByRole("link", { name: "Open reports" }).click();
    await expect(page.getByRole("region", { name: "ATTENDANCE BY BATCH" })).toBeVisible();
  });
});
