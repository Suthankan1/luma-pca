import { test, expect, type Page } from "@playwright/test";

/** Signs in as a mock staff member (every staff account has the one STAFF role). */
async function signInAsStaff(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

async function addStaff(page: Page, email: string) {
  await page.goto("/staff/team");
  await page.locator("#staff-email").fill(email);
  await page.getByRole("button", { name: "Add staff member" }).click();
}

test.describe("Staff accounts", () => {
  // Shared mock backend: emails and codes are unique per browser project and spec.
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "m" : "d") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("A staff member adds staff by email only, invites and disables them", async ({ page }) => {
    await signInAsStaff(page);
    await page.getByRole("link", { name: /Staff$/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/team$/);
    await expect(page.locator(".dir-list")).toContainText("admin-enrolled@example.invalid (you)");
    await expect(page.locator(".role-choices")).toHaveCount(0);

    await addStaff(page, `teacher.${p}@example.invalid`);
    await expect(page.locator("#staff-feedback")).toHaveText(`teacher.${p}@example.invalid added. Open their record to send an invitation.`);
    await addStaff(page, `teacher.${p}@example.invalid`);
    await expect(page.locator("#staff-feedback")).toHaveText("An account with this email already exists");
    await expect(page.locator("#staff-email")).toHaveValue(`teacher.${p}@example.invalid`, { timeout: 2000 });

    await page.goto("/staff/team");
    await page.getByRole("link", { name: `teacher.${p}@example.invalid` }).click();
    await expect(page.locator(".sub").first()).toContainText("Staff · Invitation pending");
    await expect(page.getByRole("button", { name: "Save roles" })).toHaveCount(0);
    await page.getByRole("button", { name: "Create invitation link" }).click();
    await expect(page.getByLabel("Invitation link")).toHaveValue(/\/activate#token=[A-Z]{43}$/);

    await addStaff(page, `leaver.${p}@example.invalid`);
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
    await signInAsStaff(page);
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
    await signInAsStaff(page);
    await addStaff(page, `mailed.${p}@example.invalid`);
    await page.getByRole("link", { name: "Open their record →" }).click();
    await page.getByRole("button", { name: "Email invitation" }).click();
    await expect(page.locator("#invite-feedback")).toContainText(`Invitation emailed to mailed.${p}@example.invalid. The link expires`);
    // The link itself never comes back to the staff member who sent it.
    await expect(page.getByLabel("Invitation link")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Email it again" })).toBeVisible();

    await addStaff(page, `teacher.${p}@nomail.example.invalid`);
    await page.getByRole("link", { name: "Open their record →" }).click();
    await page.getByRole("button", { name: "Email invitation" }).click();
    await expect(page.locator("#invite-feedback")).toHaveText("Email isn’t set up for the academy yet. Create a link to copy instead.");
    await page.getByRole("button", { name: "Create invitation link" }).click();
    await expect(page.getByLabel("Invitation link")).toHaveValue(/\/activate#token=[A-Z]{43}$/);
  });

  test("Nobody disables themselves; any colleague can be managed", async ({ page }) => {
    await signInAsStaff(page);
    await page.goto("/staff/team/00000000-0000-4000-8000-0000000000aa");
    await expect(page.getByText("You can’t disable your own account; ask another staff member.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Disable account" })).toHaveCount(0);
    await page.goto("/staff/team/00000000-0000-4000-8000-0000000000bb");
    await expect(page.locator(".sub").first()).toContainText("Staff · Account active");
    await expect(page.getByRole("button", { name: "Disable account" })).toBeVisible();
    await page.goto("/staff/team/00000000-0000-4000-8000-999999999999");
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    expect((await page.goto("/staff/team/not-a-uuid"))?.status()).toBe(404);
  });

  test("A batch roster has no staff assignments any more", async ({ page }) => {
    await signInAsStaff(page);
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
    await expect(page.getByRole("region", { name: "ROSTER" })).toBeVisible();
    await expect(page.getByRole("region", { name: "ASSIGNED STAFF" })).toHaveCount(0);
    await expect(page.locator("#assign-email")).toHaveCount(0);
  });
});
