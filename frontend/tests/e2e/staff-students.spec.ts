import { openSection } from "./support/ui";
import { test, expect, type Page } from "@playwright/test";

/** Owners and administrators sign in with email and password. */
async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

async function courseWithBatch(page: Page, course: string, batch: string) {
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill(course);
  await page.locator("#course-title").fill(`Course ${course}`);
  await page.getByRole("button", { name: "Create course" }).click();
  await expect(page.locator("#course-feedback")).toHaveText(`Course ${course.toUpperCase()} created.`);
  await page.locator("#batch-course").selectOption({ label: `${course.toUpperCase()} · Course ${course}` });
  await page.locator("#batch-code").fill(batch);
  await page.locator("#batch-name").fill(`Batch ${batch}`);
  await page.locator("#batch-starts").fill("2027-01-10");
  await page.getByRole("button", { name: "Create batch" }).click();
  await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${batch.toUpperCase()} created.`);
}

async function addStudent(page: Page, number: string, name: string, email: string) {
  await page.goto("/staff/students");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill(number);
  await page.locator("#student-name").fill(name);
  await page.locator("#student-email").fill(email);
  await page.getByRole("button", { name: "Add student" }).click();
}

test.describe("Staff students and enrolments", () => {
  // The mock backend is shared by both browser projects and by the other spec files,
  // so codes here are unique per project and differ from the courses spec's.
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "m" : "d") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An administrator adds a student, invites them, enrols, withdraws and re-enrols", async ({ page, context }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]).catch(() => {});
    await signInAsAdmin(page);
    await courseWithBatch(page, `stu-${p}`, `mon-${p}`);

    await addStudent(page, `pca-${p}-001`, "Kavindi Perera", `kavindi.${p}@example.invalid`);
    await expect(page.locator("#student-feedback")).toHaveText(`Student PCA-${p.toUpperCase()}-001 added. Open the record to send an invitation.`);

    await addStudent(page, `PCA-${p}-001`, "Someone Else", `other.${p}@example.invalid`);
    await expect(page.locator("#student-feedback")).toHaveText("A student with this student number already exists");
    await expect(page.locator("#student-name")).toHaveValue("Someone Else", { timeout: 2000 });

    await addStudent(page, `pca-${p}-002`, "Nimal Silva", `kavindi.${p}@example.invalid`);
    await expect(page.locator("#student-feedback")).toHaveText("An account with this email already exists");

    await page.goto("/staff/students");
    await page.getByRole("link", { name: new RegExp(`PCA-${p.toUpperCase()}-001 Kavindi Perera`) }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(`PCA-${p.toUpperCase()}-001 Kavindi Perera`);
    await expect(page.locator(".sub").first()).toContainText("Invitation pending");

    await page.getByRole("button", { name: "Create invitation link" }).click();
    const link = page.getByLabel("Invitation link");
    await expect(link).toHaveValue(/\/activate#token=[A-Z]{43}$/);
    await expect(page.getByText("This link is shown only now")).toBeVisible();
    const invitation = await link.inputValue();

    await page.locator("#enroll-batch").selectOption({ label: `STU-${p.toUpperCase()} · MON-${p.toUpperCase()} · Batch mon-${p}` });
    await page.locator("#enroll-date").fill("2027-01-12");
    await page.getByRole("button", { name: "Enrol", exact: true }).click();
    await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
    const row = page.locator(".dir-course").filter({ hasText: `MON-${p.toUpperCase()}` });
    await expect(row).toContainText("from 12 Jan 2027");
    await expect(row).toContainText("Enrolled");
    await expect(page.locator("#enroll-batch option", { hasText: `MON-${p.toUpperCase()}` })).toHaveCount(0);

    await row.getByLabel(`Withdrawal date for MON-${p.toUpperCase()}`).fill("2027-03-31");
    await row.getByRole("button", { name: `Withdraw from MON-${p.toUpperCase()}` }).click();
    await expect(row).toContainText("withdrawn 31 Mar 2027");
    await row.getByRole("button", { name: `Re-enrol in MON-${p.toUpperCase()}` }).click();
    await expect(row).not.toContainText("withdrawn");

    await page.getByRole("link", { name: new RegExp(`MON-${p.toUpperCase()}`) }).click();
    await expect(page).toHaveURL(/\/staff\/batches\//);
    await expect(page.getByRole("region", { name: "ROSTER" }).locator(".dir-list")).toContainText(`PCA-${p.toUpperCase()}-001 Kavindi Perera`);
    await expect(page.locator(".panel-sub").first()).toHaveText("1 enrolled");

    // The student follows the invitation link in a fresh browser session.
    const studentPage = await context.browser()!.newPage();
    await studentPage.goto(invitation.replace(/^https?:\/\/[^/]+/, test.info().project.use.baseURL!));
    await expect(studentPage.locator("#token")).toHaveValue(/^[A-Z]{43}$/);
    await studentPage.fill("#password", "quiet river under bridge");
    await studentPage.fill("#confirm", "quiet river under bridge");
    await studentPage.getByRole("button", { name: "Activate account" }).click();
    await expect(studentPage).toHaveURL(/\/sign-in\?activated=1$/);
    await studentPage.close();

    await page.goto("/staff/students");
    await expect(page.locator(".dir-course").filter({ hasText: `PCA-${p.toUpperCase()}-001` })).toContainText("Account active");
  });

  test("Deactivating a student hides enrolment and moves them to the inactive filter", async ({ page }) => {
    await signInAsAdmin(page);
    await addStudent(page, `pca-${p}-900`, "Leaving Student", `leaving.${p}@example.invalid`);
    await page.getByRole("link", { name: "Open the new student’s record →" }).click();
    await page.getByLabel("Active student").uncheck();
    await page.getByRole("button", { name: "Save details" }).click();
    await expect(page.locator("#details-feedback")).toHaveText("Saved.");
    await expect(page.getByText("Reactivate the student to enrol them in a batch.")).toBeVisible();

    await page.goto("/staff/students");
    await expect(page.getByRole("region", { name: "STUDENT DIRECTORY" }).locator(".dir-list")).not.toContainText(`PCA-${p.toUpperCase()}-900`);
    await page.getByRole("link", { name: "Inactive", exact: true }).click();
    await expect(page).toHaveURL(/status=inactive/);
    await expect(page.getByRole("region", { name: "STUDENT DIRECTORY" }).locator(".dir-list")).toContainText(`PCA-${p.toUpperCase()}-900 Leaving Student`);
  });

  test("Instructors see explanations, their batch list, and no student records", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/students");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators manage student records");
    await expect(page.getByRole("button", { name: "Add student" })).toHaveCount(0);
    await page.goto("/staff/courses");
    await expect(page.getByText("You are not assigned to any batch yet.")).toBeVisible();
    await page.goto("/staff/students/00000000-0000-4000-8000-000000000001");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators manage student records");
  });

  test("Unknown or malformed ids show not found, and the student page fits a phone", async ({ page }, testInfo) => {
    await signInAsAdmin(page);
    for (const path of ["/staff/students/not-a-uuid", "/staff/batches/not-a-uuid"]) {
      expect((await page.goto(path))?.status(), path).toBe(404);
      await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    }
    // Well-formed but unknown: found out while the page streams, so the not-found page shows with the status already sent.
    for (const path of ["/staff/students/00000000-0000-4000-8000-999999999999", "/staff/batches/00000000-0000-4000-8000-999999999999"]) {
      await page.goto(path);
      await expect(page.getByRole("heading", { name: "Page not found" }), path).toBeVisible();
    }
    if (testInfo.project.name !== "mobile") return;
    await addStudent(page, "pca-m-phone", "Phone Layout", "phone.m@example.invalid");
    await page.getByRole("link", { name: "Open the new student’s record →" }).click();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
