import { test, expect } from "@playwright/test";

const passphrase = "quiet river under bridge";
let code = "A".repeat(43);

test.describe("Account activation", () => {
  // Each browser project gets its own single-use code from the mock backend.
  test.beforeEach(({}, testInfo) => {
    code = (testInfo.project.name === "mobile" ? "M" : "A").repeat(43);
  });

  test("Invalid, mistyped and weak attempts keep the person on the page, then activation succeeds", async ({ page }) => {
    await page.goto(`/activate#token=${"U".repeat(43)}`);
    await expect(page.getByRole("heading", { name: "Activate your account" })).toBeVisible();
    await expect(page.locator("#token")).toHaveValue("U".repeat(43));
    await expect(page).toHaveURL(/\/activate$/);

    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page).toHaveURL(/\/activate\?error=invalid$/);
    await expect(page.locator("p.session-error[role=alert]")).toContainText("invalid, already used or expired");
    await expect(page.locator("#token")).toHaveValue("", { timeout: 2000 });

    // Paste the whole invitation link into the field.
    await page.fill("#token", `https://luma.example/activate#token=${code}`);
    await page.fill("#password", passphrase);
    await page.fill("#confirm", `${passphrase}!`);
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page.locator("p.session-error[role=alert]")).toContainText("don’t match");
    await expect(page.locator("#token")).toHaveValue(code, { timeout: 2000 });
    await expect(page).toHaveURL(/\/activate\?error=mismatch$/);

    await page.fill("#password", "a weak but long passphrase");
    await page.fill("#confirm", "a weak but long passphrase");
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page.locator("p.session-error[role=alert]")).toContainText("12 to 128 characters");
    await expect(page.locator("#token")).toHaveValue(code, { timeout: 2000 });

    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page).toHaveURL(/\/sign-in\?activated=1$/);
    await expect(page.getByRole("status")).toContainText("Your account is active");

    // A used code is refused the second time.
    await page.goto(`/activate#token=${code}`);
    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page).toHaveURL(/\/activate\?error=invalid$/);
  });

  test("Garbage instead of a code is caught without calling the backend", async ({ page }) => {
    await page.goto("/activate");
    await page.fill("#token", "not a code");
    await page.fill("#password", passphrase);
    await page.fill("#confirm", passphrase);
    await page.getByRole("button", { name: "Activate account" }).click();
    await expect(page).toHaveURL(/\/activate\?error=token$/);
    await expect(page.locator("p.session-error[role=alert]")).toContainText("Enter the activation code");
  });

  test("Activation refuses cross-site form posts and is labelled for assistive technology", async ({ page, request }) => {
    const response = await request.post("/api/session/activate", {
      headers: { Origin: "https://attacker.example", "Content-Type": "application/x-www-form-urlencoded" },
      data: `token=${code}&password=x&confirm=x`,
      maxRedirects: 0,
    });
    expect(response.status()).toBe(403);

    await page.goto("/activate");
    await expect(page.getByLabel("Activation code")).toBeVisible();
    await expect(page.getByLabel("New password", { exact: true })).toHaveAttribute("autocomplete", "new-password");
    await expect(page.getByLabel("Confirm new password", { exact: true })).toHaveAttribute("autocomplete", "new-password");
  });
});
