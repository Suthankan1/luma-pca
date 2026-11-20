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

  test("An administrator reads one staff member's history, newest first, and filters by area", async ({ page }) => {
    await signInAsAdmin(page);
    const email = `logged.${p}@example.invalid`;
    await page.goto("/staff/team");
    await page.locator("#staff-email").fill(email);
    await page.locator(".role-choices").getByLabel("Instructor", { exact: true }).check();
    await page.getByRole("button", { name: "Add staff member" }).click();
    await page.getByRole("link", { name: "Open their record →" }).click();
    await page.getByLabel("Sign this person out everywhere and block sign-in").check();
    await page.getByRole("button", { name: "Disable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Account disabled");
    await page.getByRole("button", { name: "Re-enable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Invitation pending");

    // Every staff page fits a phone, the record page included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
    await page.getByRole("link", { name: "See this account’s activity →" }).click();
    await expect(page).toHaveURL(/\/staff\/activity\?target=[0-9a-f-]{36}$/);
    const rows = page.locator(".activity-list > li");
    await expect(rows.locator("strong")).toHaveText([`Re-enabled ${email}`, `Disabled ${email}`, `Added staff member ${email}`]);
    await expect(rows.first()).toContainText("by admin-enrolled@example.invalid");
    await expect(rows.first()).toContainText("A new password is required");
    await expect(rows.last()).toContainText("Instructor");
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

  test("Instructors are told the log is for owners and administrators", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/activity");
    await expect(page.locator(".banner")).toHaveText("The activity log is for academy owners and administrators.");
  });
});
