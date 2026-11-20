import { expect, test } from "@playwright/test";

test("student data stays private across navigation, logout and a student sign-in", async ({ page }) => {
  await page.goto("/sign-in?portal=staff");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
  const response = await page.goto("/staff/students");
  expect(response?.headers()["cache-control"]).toMatch(/\bprivate\b.*\bno-store\b/);
  await expect(page.getByRole("heading", { name: "Student directory" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/sign-in/);
  await page.goBack({ waitUntil: "domcontentloaded" });
  await expect(page).toHaveURL(/\/sign-in/);
  await expect(page.getByRole("heading", { name: "Student directory" })).toHaveCount(0);
  await page.goto("/sign-in");
  await page.fill("#email", "student@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
  await page.goto("/staff/students");
  await expect(page).toHaveURL(/\/student$/);
  await expect(page.getByRole("heading", { name: "Student directory" })).toHaveCount(0);
});
