import { test, expect, type Page } from "@playwright/test";

/** Signs in as the mock administrator (role ACADEMY_ADMIN, not owner) with MFA. */
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

async function addStaff(page: Page, email: string, roles: string[]) {
  await page.goto("/staff/team");
  await page.locator("#staff-email").fill(email);
  for (const role of roles) await page.locator(".role-choices").getByLabel(role, { exact: true }).check();
  await page.getByRole("button", { name: "Add staff member" }).click();
}

test.describe("Staff accounts and batch assignments", () => {
  // Shared mock backend: emails and codes are unique per browser project and spec.
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "m" : "d") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An administrator adds staff, changes roles, invites and disables, within the role rules", async ({ page }) => {
    await signInAsAdmin(page);
    await page.getByRole("link", { name: /Staff$/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/team$/);
    await expect(page.locator(".dir-list")).toContainText("admin-enrolled@example.invalid (you)");
    await expect(page.locator(".role-choices").getByLabel("Owner", { exact: true })).toHaveCount(0);
    await expect(page.locator(".role-choices").getByLabel("Administrator", { exact: true })).toHaveCount(0);

    await addStaff(page, `teacher.${p}@example.invalid`, ["Instructor"]);
    await expect(page.locator("#staff-feedback")).toHaveText(`teacher.${p}@example.invalid added. Open their record to send an invitation.`);
    await addStaff(page, `teacher.${p}@example.invalid`, ["Analyst"]);
    await expect(page.locator("#staff-feedback")).toHaveText("An account with this email already exists");
    await expect(page.locator("#staff-email")).toHaveValue(`teacher.${p}@example.invalid`, { timeout: 2000 });
    await expect(page.locator(".role-choices").getByLabel("Analyst", { exact: true })).toBeChecked();

    await page.goto("/staff/team");
    await page.getByRole("link", { name: `teacher.${p}@example.invalid` }).click();
    await expect(page.locator(".sub").first()).toContainText("Instructor · Invitation pending");
    await page.locator(".role-choices").getByLabel("Attendance officer", { exact: true }).check();
    await page.getByRole("button", { name: "Save roles" }).click();
    await expect(page.locator("#roles-feedback")).toHaveText("Roles saved. They apply from the person’s next request.");
    await expect(page.locator(".sub").first()).toContainText("Instructor, Attendance officer");
    await page.getByRole("button", { name: "Create invitation link" }).click();
    await expect(page.getByLabel("Invitation link")).toHaveValue(/\/activate#token=[A-Z]{43}$/);

    await addStaff(page, `leaver.${p}@example.invalid`, ["Analyst"]);
    await page.getByRole("link", { name: "Open their record →" }).click();
    await expect(page.getByRole("button", { name: "Disable account" })).toBeVisible();
    await page.getByLabel("Sign this person out everywhere and block sign-in").check();
    await page.getByRole("button", { name: "Disable account" }).click();
    // The page re-renders with the new state; the disable form is gone because there is nothing left to disable.
    await expect(page.getByText("Disabled accounts cannot sign in.")).toBeVisible();
    await expect(page.locator(".sub").first()).toContainText("Account disabled");
    await page.reload();
    await expect(page.locator(".sub").first()).toContainText("Account disabled");
    await expect(page.getByRole("button", { name: "Disable account" })).toHaveCount(0);

    // Never activated: re-enabling returns the account to waiting for an invitation.
    await expect(page.getByText(`leaver.${p}@example.invalid never set a password, so they will need a new invitation.`)).toBeVisible();
    await page.getByRole("button", { name: "Re-enable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Invitation pending");
    await expect(page.getByRole("button", { name: "Create invitation link" })).toBeVisible();
  });

  test("A disabled staff member is re-enabled with their password, or made to choose a new one", async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto("/staff/team");
    await page.getByRole("link", { name: `former.${p}@example.invalid` }).click();
    await page.getByLabel("Sign this person out everywhere and block sign-in").check();
    await page.getByRole("button", { name: "Disable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Account disabled");

    await expect(page.getByText(/can sign in again with their existing password/)).toBeVisible();
    await page.getByRole("button", { name: "Re-enable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Account active");
    await expect(page.getByRole("button", { name: "Disable account" })).toBeVisible();

    await page.getByLabel("Sign this person out everywhere and block sign-in").check();
    await page.getByRole("button", { name: "Disable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Account disabled");
    await page.getByLabel(/Make them choose a new password/).check();
    await page.getByRole("button", { name: "Re-enable account" }).click();
    await expect(page.locator(".sub").first()).toContainText("Invitation pending");
    await expect(page.getByRole("button", { name: "Create invitation link" })).toBeVisible();
  });

  test("Invitations are emailed to the person, or refused clearly when email is not set up", async ({ page }) => {
    await signInAsAdmin(page);
    await addStaff(page, `mailed.${p}@example.invalid`, ["Instructor"]);
    await page.getByRole("link", { name: "Open their record →" }).click();
    await page.getByRole("button", { name: "Email invitation" }).click();
    await expect(page.locator("#invite-feedback")).toContainText(`Invitation emailed to mailed.${p}@example.invalid. The link expires`);
    // The link itself never comes back to the administrator.
    await expect(page.getByLabel("Invitation link")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Email it again" })).toBeVisible();

    await addStaff(page, `teacher.${p}@nomail.example.invalid`, ["Instructor"]);
    await page.getByRole("link", { name: "Open their record →" }).click();
    await page.getByRole("button", { name: "Email invitation" }).click();
    await expect(page.locator("#invite-feedback")).toHaveText("Email isn’t set up for the academy yet. Create a link to copy instead.");
    await page.getByRole("button", { name: "Create invitation link" }).click();
    await expect(page.getByLabel("Invitation link")).toHaveValue(/\/activate#token=[A-Z]{43}$/);
  });

  test("Nobody can change themselves, and an administrator cannot touch an owner", async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto("/staff/team/00000000-0000-4000-8000-0000000000aa");
    await expect(page.getByText("You can’t change your own roles; ask another owner.")).toBeVisible();
    await expect(page.getByText("You can’t disable your own account.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Save roles" })).toHaveCount(0);
    await page.goto("/staff/team/00000000-0000-4000-8000-0000000000bb");
    await expect(page.getByText("Only an owner can change an owner’s or administrator’s roles.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Disable account" })).toHaveCount(0);
    await page.goto("/staff/team/00000000-0000-4000-8000-999999999999");
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    expect((await page.goto("/staff/team/not-a-uuid"))?.status()).toBe(404);
  });

  test("Instructors and attendance officers are assigned to batches and removed", async ({ page }) => {
    await signInAsAdmin(page);
    await addStaff(page, `assignee.${p}@example.invalid`, ["Instructor"]);
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`tea-${p}`);
    await page.locator("#course-title").fill("Team course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `TEA-${p.toUpperCase()} · Team course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");
    await page.locator(".dir-course").filter({ hasText: `TEA-${p.toUpperCase()}` }).getByRole("link", { name: /A Batch A/ }).click();
    await expect(page.getByText("No one is assigned yet.")).toBeVisible();

    await page.locator("#assign-email").fill("nobody@example.invalid");
    await page.getByRole("button", { name: "Assign" }).click();
    await expect(page.locator("#assign-feedback")).toHaveText("No staff account with that email in this academy.");
    await page.locator("#assign-email").fill("analyst@example.invalid");
    await page.getByRole("button", { name: "Assign" }).click();
    await expect(page.locator("#assign-feedback")).toHaveText("The account does not hold the INSTRUCTOR role");

    await page.locator("#assign-email").fill(`assignee.${p}@example.invalid`);
    await page.getByRole("button", { name: "Assign" }).click();
    await expect(page.locator("#assign-feedback")).toHaveText("Assigned. They can open this roster from their next request.");
    const row = page.locator(".dir-course").filter({ hasText: `assignee.${p}@example.invalid` });
    await expect(row).toContainText("Instructor");
    await row.getByRole("button", { name: `Remove assignee.${p}@example.invalid as Instructor` }).click();
    await expect(page.getByText("No one is assigned yet.")).toBeVisible();
  });

  test("Instructors cannot manage staff", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.fill("#code", "rec-recovery");
    await page.getByRole("button", { name: "Verify" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/team");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators manage staff accounts.");
    await expect(page.getByRole("button", { name: "Add staff member" })).toHaveCount(0);
  });
});
