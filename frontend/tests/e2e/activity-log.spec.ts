import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Activity log", () => {
  // The mock backend is shared across projects and specs: the person is unique per project.
  let p = "ad";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "am" : "ad") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("A staff member reads one record's history and filters by area", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-hx`);
    await page.locator("#course-title").fill("History course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${P}-HX · History course` });
    await page.locator("#batch-code").fill(`${p}-mon`);
    await page.locator("#batch-name").fill("Monday");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    const lesson = await scheduleClass(page, { title: `Logged ${p}`, date: "2027-04-05", start: "18:00", end: "20:00",
      batch: `${P}-HX · ${P}-MON · Monday` });

    await page.goto(`/staff/activity?target=${lesson.id}`);
    const rows = page.locator(".activity-list > li");
    await expect(rows.locator("strong")).toHaveText([`Scheduled “Logged ${p}”`]);
    await expect(rows.first()).toContainText("by admin-enrolled@example.invalid");
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);

    // Areas narrow the same record's history; "Show everything" drops the record filter.
    await page.getByRole("navigation", { name: "Filter activity" }).getByRole("link", { name: "Students", exact: true }).click();
    await expect(page).toHaveURL(/category=students/);
    await expect(page.getByText("Nothing recorded here yet.")).toBeVisible();
    await page.getByRole("link", { name: "Show everything" }).click();
    await expect(page).toHaveURL(/\/staff\/activity\?category=students$/);
    for (const summary of await rows.locator("strong").allTextContents()) expect(summary).toMatch(/^Imported \d+ students?$/);
    await expect(page.getByRole("complementary", { name: "Staff navigation" }).getByRole("link", { name: /Activity log/ }))
      .toHaveAttribute("aria-current", "page");
  });

  test("Scheduling a class appears under Classes, in the class's own time", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-tt`);
    await page.locator("#course-title").fill("Timetable course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${P}-TT · Timetable course` });
    await page.locator("#batch-code").fill(`${p}-sat`);
    await page.locator("#batch-name").fill("Saturday");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Audited ${p}`, date: "2027-04-03", start: "09:00", end: "11:00", batch: `${P}-TT · ${P}-SAT · Saturday` });

    await page.goto("/staff/activity?category=classes");
    const entry = page.locator(".activity-list > li").filter({ hasText: `Scheduled “Audited ${p}”` });
    await expect(entry).toContainText("Sat 3 Apr 2027 · 09:00–11:00");
    await expect(entry).toContainText("by admin-enrolled@example.invalid");
  });
});
