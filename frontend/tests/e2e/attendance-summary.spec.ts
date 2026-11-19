import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Attendance summaries", () => {
  let p = "sd";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "sm" : "sd") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("Staff see each student's rate in a batch; analysts see batch totals without names", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    const C = `SUM-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`sum-${p}`);
    await page.locator("#course-title").fill("Summary course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Summary course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    for (const n of ["1", "2"]) {
      await page.goto("/staff/students");
      await openSection(page, "Add a student who is not in the Academy records");
      await page.locator("#student-number").fill(`${C}-00${n}`);
      await page.locator("#student-name").fill(`Summary Student ${n}`);
      await page.locator("#student-email").fill(`summary.${p}${n}@example.invalid`);
      await page.getByRole("button", { name: "Add student" }).click();
      await page.getByRole("link", { name: "Open the new student’s record →" }).click();
      await page.locator("#enroll-batch").selectOption({ label: `${C} · A · Batch A` });
      await page.locator("#enroll-date").fill("2027-01-02");
      await page.getByRole("button", { name: "Enrol", exact: true }).click();
      await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
    }
    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Summary class ${p}`, date: "2027-01-04", start: "18:00", end: "20:00", batch: `${C} · A · Batch A` });

    await page.goto("/staff/sessions?from=2027-01-04");
    await page.getByRole("link", { name: new RegExp(`Summary class ${p}`) }).first().click();
    await page.getByRole("link", { name: "Roster →" }).first().click();
    const summary = page.getByRole("region", { name: "ATTENDANCE SUMMARY" });
    await summary.getByLabel("From").fill("2027-01-01");
    await summary.getByLabel("To").fill("2027-01-31");
    await summary.getByRole("button", { name: "Show summary" }).click();
    await expect(page).toHaveURL(/sumFrom=2027-01-01&sumTo=2027-01-31/);
    await expect(summary).toContainText("batch rate 75%");
    const rows = summary.getByRole("listitem", { name: /^Summary / });
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText(`${C}-001`);
    await expect(rows.first()).toContainText("50%");
    await expect(rows.nth(1)).toContainText("100%");

    await page.goto("/staff/attendance?section=totals");
    await expect(page.getByRole("region", { name: "ATTENDANCE BY BATCH" }).getByRole("listitem", { name: `Batch ${C} A` }))
      .toContainText("75%");

    await signInStaff(page, "analyst@example.invalid");
    await page.goto("/staff/attendance");
    const report = page.getByRole("region", { name: "ATTENDANCE BY BATCH" });
    await expect(report.getByRole("listitem", { name: `Batch ${C} A` })).toContainText("2 final classes (1 not final yet)");
    await expect(report).not.toContainText(`${C}-001`);
    await expect(report.getByRole("link")).toHaveCount(0);
    await expect(page.getByRole("region", { name: "APPEALS" })).toHaveCount(0);
    // Analysts read the rule under its own section; they have no to-do list or per-student reports.
    const sections = page.getByRole("navigation", { name: "Attendance sections" });
    await expect(sections.getByRole("link", { name: "To do" })).toHaveCount(0);
    await sections.getByRole("link", { name: "Rule & settings" }).click();
    await expect(page.getByRole("region", { name: "ATTENDANCE RULE" })).toBeVisible();
  });
});
