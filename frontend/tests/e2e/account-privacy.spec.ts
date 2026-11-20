import { openSection } from "./support/ui";
import { test, expect } from "@playwright/test";

test("A student changes their password with the current one and is signed out", async ({ page }) => {
  await page.goto("/sign-in?portal=student");
  await page.fill("#email", "student@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
  await page.getByRole("link", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/account\/password$/);

  await page.fill("#current", "not my password");
  await page.fill("#password", "a brand new long passphrase");
  await page.fill("#confirm", "a brand new long passphrase");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.locator(".session-error")).toHaveText("That isn’t your current password.");

  await page.fill("#current", "correct-password");
  await page.fill("#password", "a brand new long passphrase");
  await page.fill("#confirm", "a different long passphrase");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.locator(".session-error")).toHaveText("The two new passwords don’t match.");

  await page.fill("#current", "correct-password");
  await page.fill("#password", "a brand new long passphrase");
  await page.fill("#confirm", "a brand new long passphrase");
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page).toHaveURL(/\/sign-in\?changed=1$/);
  await expect(page.getByRole("status")).toContainText("Your password has been changed");
});

test("An owner erases a student's personal data after typing the number again", async ({ page }, testInfo) => {
  const p = (testInfo.project.name === "mobile" ? "EM" : "ED") + (testInfo.retry ? String(testInfo.retry) : "");
  await page.goto("/sign-in");
  await page.fill("#email", "owner-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);

  await page.goto("/staff/students");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill(`${p}-1`);
  await page.locator("#student-name").fill("Leaving Student");
  await page.locator("#student-email").fill(`leaving.${p.toLowerCase()}@example.invalid`);
  await page.getByRole("button", { name: "Add student" }).click();
  await page.getByRole("link", { name: "Open the new student’s record →" }).click();

  await page.getByText("Erase this student’s personal data").click();
  await page.locator("#erase-confirm").fill("WRONG-1");
  await page.locator("#erase-reason").fill("Request from the guardian");
  await page.getByRole("button", { name: "Erase personal data" }).click();
  await expect(page.locator("#erase-feedback")).toHaveText("That isn’t this student’s number.");
  await page.locator("#erase-confirm").fill(`${p}-1`);
  await page.locator("#erase-reason").fill("Request from the guardian");
  await page.getByRole("button", { name: "Erase personal data" }).click();
  await expect(page.locator(".heading").first()).toContainText("Erased student");
});
