import { expect, test } from "@playwright/test";

test.describe("Student ID sign-in (Academy request)", () => {
  test("A student signs in with their registration ID alone and sees their attendance", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: /Student sign in/ }).click();
    const students = page.getByRole("form", { name: "Students" });
    await expect(page.locator('input[type="email"]')).toHaveCount(0);
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await expect(students.getByLabel("Registration ID")).toBeFocused();
    await students.getByLabel("Registration ID").fill(" pca001 ");
    await students.getByRole("button", { name: "View my attendance" }).click();
    await expect(page).toHaveURL(/\/student$/);
    await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  });

  test("An unknown registration ID is refused with a student-specific message", async ({ page }) => {
    await page.goto("/sign-in");
    const students = page.getByRole("form", { name: "Students" });
    await students.getByLabel("Registration ID").fill("PCA002");
    await students.getByRole("button", { name: "View my attendance" }).click();
    await expect(page).toHaveURL(/\/sign-in\?error=student&portal=student$/);
    await expect(page.locator(".session-error")).toHaveText("We couldn’t find that registration ID. Check it and try again.");
  });

  test("The staff sign-in page shows only the staff form", async ({ page }) => {
    await page.goto("/sign-in?portal=staff");
    await expect(page.getByRole("form", { name: "Students" })).toHaveCount(0);
    await expect(page.locator("#email")).toBeVisible();
  });
});
