import { openSection } from "./support/ui";
import { test, expect, type Page } from "@playwright/test";
import { scheduleClass } from "./support/classes";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

/** A course with batches A and B, and one student enrolled in A from 5 Jan 2027. */
async function setUp(page: Page, code: string) {
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill(code);
  await page.locator("#course-title").fill("Class course");
  await page.getByRole("button", { name: "Create course" }).click();
  for (const batch of ["A", "B"]) {
    await page.locator("#batch-course").selectOption({ label: `${code.toUpperCase()} · Class course` });
    await page.locator("#batch-code").fill(batch);
    await page.locator("#batch-name").fill(`Batch ${batch}`);
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${batch} created.`);
  }
  await page.goto("/staff/students");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill(`${code}-001`);
  await page.locator("#student-name").fill("Class Student");
  await page.locator("#student-email").fill(`${code}.student@example.invalid`);
  await page.getByRole("button", { name: "Add student" }).click();
  await page.getByRole("link", { name: "Open the new student’s record →" }).click();
  await page.locator("#enroll-batch").selectOption({ label: `${code.toUpperCase()} · A · Batch A` });
  await page.locator("#enroll-date").fill("2027-01-05");
  await page.getByRole("button", { name: "Enrol", exact: true }).click();
  await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
}

test.describe("Classes (Zoom owns schedules)", () => {
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "m" : "d") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("Classes are read-only: no scheduling, editing, moving or cancelling controls remain", async ({ page }) => {
    await signInAsAdmin(page);
    await setUp(page, `cl-${p}`);
    const P = `CL-${p.toUpperCase()}`;
    // A class from before the change (history), created directly in the API.
    const old = await scheduleClass(page, { title: `Waves ${p}`, date: "2027-01-06", start: "18:00", end: "20:00", batch: `${P} · A · Batch A` });

    await page.goto("/staff/sessions?from=2027-01-03");
    for (const name of ["Schedule class", "Create series", "Check the series", "Save changes", "Cancel class", "Create classes"]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }
    await expect(page.locator("#schedule-title, #series-title")).toHaveCount(0);
    const where = page.getByRole("region", { name: "WHERE CLASSES COME FROM" });
    await expect(where).toContainText("Zoom owns the schedule");
    await expect(where.getByRole("link", { name: "Webinars →" })).toHaveAttribute("href", "/staff/webinars");
    const row = page.getByRole("listitem").filter({ hasText: `Waves ${p}` });
    await expect(row).toContainText("Wed 6 Jan 2027 · 18:00–20:00");
    await expect(row.locator(".status")).toHaveText("Scheduled before");

    // Its page keeps the expected students and attendance, without any way to change the class.
    await page.goto(`/staff/sessions/${old.id}`);
    await expect(page.getByRole("region", { name: "EXPECTED STUDENTS" })).toContainText(`cl-${p}-001`.toUpperCase());
    await expect(page.getByRole("region", { name: "ZOOM", exact: true })).toContainText("can no longer be edited or cancelled here");
    await expect(page.locator("#edit-title, #edit-date, #status-select")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Save changes|Cancel class|Restore class/ })).toHaveCount(0);

    // The old import route now leads to monitoring.
    await page.goto("/staff/zoom/import");
    await expect(page).toHaveURL(/\/staff\/webinars$/);
  });
});
