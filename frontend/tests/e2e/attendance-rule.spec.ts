import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signIn(page: Page, email: string) {
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Attendance rule and final attendance", () => {
  // The mock backend is shared across projects and specs: values and codes are unique per project.
  let p = "fd";
  let n = "1";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "fm" : "fd") + (testInfo.retry ? String(testInfo.retry) : "");
    n = p === "fm" ? "2" : "1";
  });

  test("Any staff member records the academy's rule and every colleague sees it", async ({ page }) => {
    await signIn(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance?section=settings");
    await page.locator("#policy-late").fill("10");
    await page.locator("#policy-description").fill(`Approved at the staff meeting (${p})`);
    await page.locator("#policy-percent").fill("75");
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");
    await expect(page.locator(".rule-summary")).toContainText("at least 75% of the class time");
    await expect(page.locator(".rule-summary")).toContainText("more than 10 minutes after the start");

    await page.context().clearCookies();
    await signIn(page, "admin-enrolled@example.invalid");
    await page.getByRole("link", { name: /Attendance$/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/attendance$/);
    await expect(page.locator(".rule-line")).toContainText("present from 75% of the class");
    await page.getByRole("navigation", { name: "Attendance sections" }).getByRole("link", { name: "Rule & settings" }).click();
    await expect(page.locator(".rule-summary")).toContainText("75%");
    await expect(page.getByText("Only an academy owner can record or change the rule.")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Save rule" })).toBeVisible();
  });

  test("An administrator finalises a finished class from Zoom's report, by the academy's rule", async ({ page }) => {
    await signIn(page, "admin-enrolled@example.invalid");
    const C = `FIN-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`fin-${p}`);
    await page.locator("#course-title").fill("Final course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Final course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2026-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");

    await page.goto("/staff/students");
    await openSection(page, "Add a student who is not in the Academy records");
    await page.locator("#student-number").fill(`FN-${p.toUpperCase()}-1`);
    await page.locator("#student-name").fill("Final Student");
    await page.locator("#student-email").fill(`final.student${n}@example.invalid`);
    await page.getByRole("button", { name: "Add student" }).click();
    await page.getByRole("link", { name: "Open the new student’s record →" }).click();
    await page.locator("#enroll-batch").selectOption({ label: `${C} · A · Batch A` });
    await page.locator("#enroll-date").fill("2026-01-05");
    await page.getByRole("button", { name: "Enrol", exact: true }).click();
    await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");

    // A past class on the seeded webinar, 09:00–11:00 Colombo; the student joined at 09:15 and stayed.
    const day = `2026-03-0${Number(n) + 4}`;
    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Final class ${p}`, date: day, start: "09:00", end: "11:00", batch: `${C} · A · Batch A`, webinar: `444 0001 110${n}` });

    await page.goto("/staff/zoom");
    const occurrence = page.getByRole("listitem", { name: `Webinar 444 0001 110${n} at ${Number(n) + 4} Mar 2026 09:05` });
    await expect(occurrence.locator(".status")).toHaveText("Linked automatically");
    await occurrence.getByRole("link", { name: new RegExp(`^Final class ${p}, `) }).click();
    // Live data only: not final, and no finalise button.
    await expect(page.getByRole("region", { name: "PROVISIONAL ATTENDANCE" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Finalise attendance" })).toHaveCount(0);

    await page.goto("/staff/zoom");
    await occurrence.getByRole("button", { name: "Fetch Zoom’s report" }).click();
    await expect(occurrence.locator(".report-feedback")).toHaveText("Report fetched: 2 join sessions.");
    await occurrence.getByRole("link", { name: new RegExp(`^Final class ${p}, `) }).click();
    await page.getByRole("button", { name: "Finalise attendance" }).click();
    const final = page.getByRole("region", { name: "FINAL ATTENDANCE" });
    await expect(final.getByRole("listitem", { name: `Attendance FN-${p.toUpperCase()}-1 Final Student` }).locator(".status")).toHaveText("Late");
    const metric = (label: string) => page.locator(".session-metrics > div").filter({ has: page.getByText(label, { exact: true }) }).locator("dd strong");
    await expect(metric("Expected")).toHaveText("1");
    await expect(metric("Students joined")).toHaveText("1");
    await expect(metric("Expected not seen")).toHaveText("0");
    await expect(metric("Attendance credited")).toHaveText("1");
    await expect(metric("Average time connected")).toHaveText("105 min");
    await expect(final).toContainText("Final since");
    await expect(page.getByRole("button", { name: "Finalise attendance" })).toHaveCount(0);

    // A correction with a reason; the computed outcome stays visible in the student's history.
    const row = final.getByRole("listitem", { name: `Attendance FN-${p.toUpperCase()}-1 Final Student` });
    await row.getByText("Correct", { exact: true }).click();
    await row.getByLabel("Corrected outcome").selectOption({ label: "Excused" });
    await row.getByLabel(/^Reason/).fill("Medical note received");
    await row.getByRole("button", { name: "Save correction" }).click();
    await expect(row.locator(".status")).toHaveText("Excused");
    await expect(metric("Attendance credited")).toHaveText("0");
    await page.getByText("Recalculate from Zoom’s report").click();
    await page.locator("#recalc-reason").fill("Zoom report fetched again");
    await page.getByRole("button", { name: "Recalculate" }).click();
    await expect(page.locator("#recalc-feedback")).toHaveText("Recalculated: 0 changed, 1 corrected kept, 0 added.");
    await expect(row.locator(".status")).toHaveText("Excused");
    await page.goto("/staff/students?q=" + `FN-${p.toUpperCase()}-1`);
    await page.getByRole("link", { name: new RegExp(`FN-${p.toUpperCase()}-1`) }).first().click();
    const history = page.getByRole("region", { name: "ATTENDANCE HISTORY" });
    await expect(history.getByRole("listitem", { name: `History Final class ${p}` })).toContainText("corrected from late");
    await expect(history.getByRole("listitem", { name: `History Final class ${p}` }).locator(".status")).toHaveText("Excused");
  });
});
