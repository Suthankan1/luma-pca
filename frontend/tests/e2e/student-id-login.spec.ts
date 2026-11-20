import { expect, test } from "@playwright/test";

test.describe("Student ID sign-in (Academy request)", () => {
  test("A student signs in with their student ID as username and password", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: /Student sign in/ }).click();
    const students = page.getByRole("form", { name: "Students" });
    await expect(page.locator('input[type="email"]')).toHaveCount(0);
    await expect(students.getByLabel("Username")).toHaveAttribute("type", "text");
    await students.getByLabel("Username").fill(" pca001 ");
    await students.getByLabel("Password").fill("PCA001");
    await students.getByRole("button", { name: "Open my student portal" }).click();
    await expect(page).toHaveURL(/\/student$/);
    await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  });

  test("A wrong password is refused with a student-specific message", async ({ page }) => {
    await page.goto("/sign-in");
    const students = page.getByRole("form", { name: "Students" });
    await students.getByLabel("Username").fill("PCA001");
    await students.getByLabel("Password").fill("PCA002");
    await students.getByRole("button", { name: "Open my student portal" }).click();
    await expect(page).toHaveURL(/\/sign-in\?error=student&portal=student$/);
    await expect(page.locator(".session-error")).toHaveText("Check your student ID and try again.");
  });

  test("The staff sign-in page shows only the staff form", async ({ page }) => {
    await page.goto("/sign-in?portal=staff");
    await expect(page.getByRole("form", { name: "Students" })).toHaveCount(0);
    await expect(page.locator("#email")).toBeVisible();
  });
});
