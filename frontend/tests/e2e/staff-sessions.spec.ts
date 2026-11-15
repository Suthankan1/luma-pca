import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
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

async function fillClass(page: Page, fields: { title: string; date: string; start: string; end: string; batches: string[]; webinar?: string }) {
  await page.locator("#schedule-title").fill(fields.title);
  await page.locator("#schedule-date").fill(fields.date);
  await page.locator("#schedule-start").fill(fields.start);
  await page.locator("#schedule-end").fill(fields.end);
  const single = page.locator("form", { has: page.locator("#schedule-title") });
  for (const batch of fields.batches) await single.getByLabel(batch, { exact: true }).check();
  if (fields.webinar) await page.locator("#schedule-webinar").fill(fields.webinar);
}

test.describe("Classes", () => {
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = testInfo.project.name === "mobile" ? "m" : "d";
  });

  test("An administrator schedules a class for two batches, edits, cancels and restores it", async ({ page }) => {
    await signInAsAdmin(page);
    const code = `ses-${p}`;
    const C = code.toUpperCase();
    await setUp(page, code);

    await page.getByRole("link", { name: /Classes$/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/sessions$/);
    await fillClass(page, { title: "Waves", date: "2027-01-11", start: "20:00", end: "18:00", batches: [`${C} · A · Batch A`] });
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText("The class must end after it starts, on the same day.");
    // A refusal keeps what was typed and chosen.
    await expect(page.locator("#schedule-title")).toHaveValue("Waves");
    const singleForm = page.locator("form", { has: page.locator("#schedule-title") });
    await expect(singleForm.getByLabel(`${C} · A · Batch A`, { exact: true })).toBeChecked();

    await page.locator("#schedule-start").fill("18:00");
    await page.locator("#schedule-end").fill("20:00");
    await singleForm.getByLabel(`${C} · B · Batch B`, { exact: true }).check();
    await page.locator("#schedule-webinar").fill("812 3456 7890");
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText("Waves scheduled.");
    await page.getByRole("link", { name: "Open the class →" }).click();

    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Waves");
    await expect(page.locator(".sub").first()).toHaveText("Mon 11 Jan 2027 · 18:00–20:00 (Asia/Colombo)");
    await expect(page.getByText("Zoom webinar: 812 3456 7890")).toBeVisible();
    const expected = page.getByRole("region", { name: "EXPECTED STUDENTS" });
    await expect(expected.locator(".dir-list").first().locator("li")).toHaveCount(1);
    await expect(expected).toContainText(`${C}-001 Class Student`);

    // Edit: a new title and an earlier date, before the student's enrolment.
    await page.locator("#edit-title").fill("Waves (moved)");
    await page.locator("#edit-date").fill("2027-01-04");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator("#edit-feedback")).toHaveText("Saved. Expected students and automatic Zoom links follow the new date, time and webinar.");
    await expect(page.locator(".sub").first()).toHaveText("Mon 4 Jan 2027 · 18:00–20:00 (Asia/Colombo)");
    await expect(page.getByText("No students are enrolled in these batches on that date.")).toBeVisible();

    await page.getByRole("button", { name: "Cancel class" }).click();
    await expect(page.locator("#status-feedback")).toHaveText("Class cancelled.");
    await expect(page.locator(".banner")).toContainText("This class is cancelled.");
    await page.getByRole("button", { name: "Restore class" }).click();
    await expect(page.locator("#status-feedback")).toHaveText("Class restored.");
    await expect(page.locator(".banner")).toHaveCount(0);

    // The list shows the fortnight from a date, and filters by batch.
    await page.goto("/staff/sessions?from=2027-01-03");
    const list = page.getByRole("region", { name: "CLASSES" });
    await expect(list).toContainText("Waves (moved)");
    await expect(list).toContainText("A, B");
    await page.getByRole("link", { name: "Next two weeks →" }).click();
    await expect(page).toHaveURL(/from=2027-01-17$/);
    await expect(list).not.toContainText("Waves (moved)");
    await page.goto("/staff/sessions?from=2027-01-03");
    await page.locator("#class-batch").selectOption({ label: `${C} · B · Batch B` });
    await page.getByRole("button", { name: "Show" }).click();
    await expect(page).toHaveURL(/batch=/);
    await expect(list).toContainText("Waves (moved)");
  });

  test("A stale edit is refused instead of overwriting someone else's change", async ({ page, context }) => {
    await signInAsAdmin(page);
    const code = `old-${p}`;
    await setUp(page, code);
    await page.goto("/staff/sessions");
    await fillClass(page, { title: "Optics", date: "2027-02-01", start: "09:00", end: "10:30", batches: [`${code.toUpperCase()} · A · Batch A`] });
    await page.getByRole("button", { name: "Schedule class" }).click();
    await page.getByRole("link", { name: "Open the class →" }).click();
    await expect(page).toHaveURL(/\/staff\/sessions\/[0-9a-f-]{36}$/);
    const other = await context.newPage();
    await other.goto(page.url());
    await other.locator("#edit-title").fill("Optics (first)");
    await other.getByRole("button", { name: "Save changes" }).click();
    await expect(other.locator("#edit-feedback")).toHaveText("Saved. Expected students and automatic Zoom links follow the new date, time and webinar.");

    await page.locator("#edit-title").fill("Optics (second)");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator("#edit-feedback")).toHaveText("Someone else changed this record. Reload the page and try again.");
    await expect(page.locator("#edit-title")).toHaveValue("Optics (second)");
    await page.reload();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Optics (first)");
    expect((await page.goto("/staff/sessions/00000000-0000-4000-8000-999999999999"))?.status()).toBe(404);
    expect((await page.goto("/staff/sessions/not-a-uuid"))?.status()).toBe(404);
  });

  test("Instructors see classes for their batches only and cannot schedule", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.fill("#code", "rec-recovery");
    await page.getByRole("button", { name: "Verify" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/sessions?from=2027-01-03");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators schedule classes.");
    await expect(page.getByText("No classes in these two weeks.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Schedule class" })).toHaveCount(0);
  });
});
