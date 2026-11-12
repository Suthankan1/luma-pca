import { test, expect } from "@playwright/test";

const passphrase = "bright lantern over harbour";

test.describe("Password reset", () => {
  // Each browser project gets its own single-use reset code from the mock backend.
  let code = "R".repeat(43);
  test.beforeEach(({}, testInfo) => {
    code = (testInfo.project.name === "mobile" ? "S" : "R").repeat(43);
  });

  test("Asking for a link gives the same answer for any address and never puts it in the URL", async ({ page }) => {
    await page.goto("/sign-in");
    await page.getByRole("link", { name: "Forgot your password?" }).click();
    await expect(page.getByRole("heading", { name: "Forgot your password" })).toBeVisible();
    await page.fill("#email", "someone@example.invalid");
    await page.getByRole("button", { name: "Email me a reset link" }).click();
    await expect(page).toHaveURL(/\/forgot-password\?sent=1$/);
    await expect(page.getByRole("status")).toContainText("If an active LUMA account uses that address, we’ve emailed it a link");
    expect(page.url()).not.toContain("someone");

    await page.goto("/forgot-password");
    await page.fill("#email", "reset-off@example.invalid");
    await page.getByRole("button", { name: "Email me a reset link" }).click();
    await expect(page).toHaveURL(/\/forgot-password\?error=disabled$/);
    await expect(page.locator("p.session-error[role=alert]")).toContainText("isn’t available yet");
  });

  test("The link fills the code, mistakes keep it, and a used link is refused", async ({ page }) => {
    await page.goto(`/reset-password#token=${code}`);
    await expect(page.getByRole("heading", { name: "Choose a new password" })).toBeVisible();
    await expect(page.locator("#token")).toHaveValue(code);
    // The code leaves the address bar so it isn't kept in history.
    await expect(page).toHaveURL(/\/reset-password$/);

    await page.fill("#password", passphrase);
    await page.fill("#confirm", `${passphrase}!`);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page.locator("p.session-error[role=alert]")).toContainText("don’t match");
    await expect(page.locator("#token")).toHaveValue(code, { timeout: 2000 });

    await page.fill("#password", "a weak but long passphrase");
    await page.fill("#confirm", "a weak but long passphrase");
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page.locator("p.session-error[role=alert]")).toContainText("12 to 128 characters");
    await expect(page.locator("#token")).toHaveValue(code, { timeout: 2000 });

    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page).toHaveURL(/\/sign-in\?reset=1$/);
    await expect(page.getByRole("status")).toContainText("Your password has been changed and you were signed out everywhere.");

    // Single use: the same link again is refused, with a way to ask for a new one.
    await page.goto(`/reset-password#token=${code}`);
    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page).toHaveURL(/\/reset-password\?error=invalid$/);
    await expect(page.locator("p.session-error[role=alert]")).toContainText("invalid, already used or expired");
    await page.getByRole("link", { name: "Email me a new reset link" }).click();
    await expect(page).toHaveURL(/\/forgot-password$/);
  });

  test("A pasted whole link works and a missing code is caught before the server", async ({ page }) => {
    await page.goto("/reset-password");
    await page.fill("#token", "not a code");
    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page).toHaveURL(/\/reset-password\?error=token$/);
    await expect(page.locator("p.session-error[role=alert]")).toContainText("Enter the code from your reset email");
  });
});
