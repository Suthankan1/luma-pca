import { test, expect } from "@playwright/test";

test.describe("MFA and Authentication Flows", () => {
  test("Owner/admin initial MFA enrollment through to recovery codes and staff portal", async ({ page, context }) => {
    // Grant clipboard permissions for copy action testing
    await context.grantPermissions(["clipboard-read", "clipboard-write"]).catch(() => {});

    await page.goto("/sign-in");
    await expect(page.getByRole("heading", { name: "Sign in to LUMA" })).toBeVisible();

    await page.fill("#email", "admin-unenrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    // Must be redirected to MFA setup
    await expect(page).toHaveURL(/\/sign-in\/mfa/);
    await expect(page.getByRole("heading", { name: "Set up authenticator" })).toBeVisible();
    await expect(page.locator(".mfa-qr svg")).toBeVisible();
    await expect(page.locator("code.mfa-secret")).toContainText("JBSW Y3DP");

    // Enter valid 6-digit TOTP code
    await page.fill("#code", "123456");
    await page.getByRole("button", { name: "Verify and save" }).click();

    // Must be redirected to one-time recovery codes presentation
    await expect(page).toHaveURL(/\/sign-in\/mfa\/recovery-codes/);
    await expect(page.getByRole("heading", { name: "Save recovery codes" })).toBeVisible();
    await expect(page.locator(".recovery-grid")).toContainText("rec-1111");
    await expect(page.locator(".recovery-grid")).toContainText("rec-2222");

    // Copy codes button test
    const copyBtn = page.getByRole("button", { name: "Copy all codes" });
    if (await copyBtn.isVisible()) {
      await copyBtn.click();
      await expect(page.getByRole("button", { name: "Copied to clipboard!" })).toBeVisible();
    }

    // Acknowledge and proceed
    await page.getByLabel("I have safely saved or written down these recovery codes").check();
    await page.getByRole("button", { name: "Continue to staff portal" }).click();

    // Must arrive at staff portal
    await expect(page).toHaveURL(/\/staff/);
    await expect(page.getByRole("heading", { name: "Staff portal" })).toBeVisible();

    // Refreshing or navigating back to recovery-codes page must redirect to staff portal
    await page.goto("/sign-in/mfa/recovery-codes");
    await expect(page).toHaveURL(/\/staff/);

    // Sign out
    await page.goto("/sign-in");
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/sign-in\?signedOut=1/);
  });

  test("Owner/admin existing MFA login with recovery code", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "admin-enrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/sign-in\/mfa/);
    await expect(page.getByRole("heading", { name: "Enter your code" })).toBeVisible();
    // No QR code on existing enrolled login
    await expect(page.locator(".mfa-qr")).toHaveCount(0);

    // Verify using a recovery code
    await page.fill("#code", "rec-recovery");
    await page.getByRole("button", { name: "Verify" }).click();

    // Direct transition to staff portal (no new recovery codes shown)
    await expect(page).toHaveURL(/\/staff/);
    await expect(page.getByRole("heading", { name: "Staff portal" })).toBeVisible();

    // Clean up
    await page.goto("/sign-in");
    await page.getByRole("button", { name: "Sign out" }).click();
  });

  test("Invalid MFA code displays clear error without leaking tokens", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "admin-unenrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/sign-in\/mfa/);
    await page.fill("#code", "000000");
    await page.getByRole("button", { name: "Verify and save" }).click();

    await expect(page).toHaveURL(/\/sign-in\/mfa\?error=code/);
    const alert = page.locator("p.session-error[role='alert']");
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("The code entered is invalid or expired");

    // Staff portal still inaccessible
    await page.goto("/staff");
    await expect(page).toHaveURL(/\/sign-in/);
  });

  test("Cancel and restart MFA clears pending challenge", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "admin-enrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/sign-in\/mfa/);
    await page.getByRole("button", { name: "Cancel" }).click();

    await expect(page).toHaveURL(/\/sign-in/);

    // Visiting /sign-in/mfa after cancel must redirect to sign-in with expired
    await page.goto("/sign-in/mfa");
    await expect(page).toHaveURL(/\/sign-in\?error=expired/);
  });

  test("Student and instructor login regression preserved", async ({ page }) => {
    // 1. Student sign-in
    await page.goto("/sign-in?portal=student");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/student/);
    await expect(page.getByRole("heading", { name: "Student portal" })).toBeVisible();

    // Student cannot access staff portal
    await page.goto("/staff");
    await expect(page).toHaveURL(/\/student/);

    // Sign out
    await page.goto("/sign-in");
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/sign-in\?signedOut=1/);

    // 2. Instructor sign-in without MFA
    await page.goto("/sign-in?portal=staff");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.fill("#code", "rec-recovery");
    await page.getByRole("button", { name: "Verify" }).click();

    await expect(page).toHaveURL(/\/staff/);
    await expect(page.getByRole("heading", { name: "Staff portal" })).toBeVisible();

    // Sign out
    await page.goto("/sign-in");
    await page.getByRole("button", { name: "Sign out" }).click();
  });
});
