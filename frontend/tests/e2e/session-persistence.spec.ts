import { expect, test } from "@playwright/test";

/**
 * The access cookie lasts as long as the short access token (minutes). Removing it simulates that expiry: the person
 * must stay signed in (renewed quietly), and Sign out must still end the session at once.
 */
test.describe("Staying signed in", () => {
  test("A student stays signed in after the short access token expires, and Sign out still works", async ({ page, context }) => {
    await context.clearCookies();
    await page.goto("/sign-in?portal=student");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student$/);

    for (let i = 0; i < 2; i++) {
      await context.clearCookies({ name: "luma-access" });
      await page.goto("/student/attendance");
      await expect(page).toHaveURL(/\/student\/attendance$/);
      await expect(page.getByRole("heading", { name: "My attendance" })).toBeVisible();
      expect((await context.cookies()).some(c => c.name === "luma-access"), "renewed").toBe(true);
    }

    await page.getByRole("complementary", { name: "Academy navigation" }).getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/sign-in\?signedOut=1$/);
    expect((await context.cookies()).filter(c => c.name.startsWith("luma-"))).toEqual([]);
    await page.goto("/student");
    await expect(page).toHaveURL(/\/sign-in\?portal=student/);
  });

  test("Staff stay signed in across an expired access token on any page", async ({ page, context }) => {
    await context.clearCookies();
    await page.goto("/sign-in?portal=staff");
    await page.fill("#email", "admin-enrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff$/);

    await context.clearCookies({ name: "luma-access" });
    await page.goto("/staff/dashboard");
    await expect(page).toHaveURL(/\/staff\/dashboard$/);
    await expect(page.getByRole("heading", { name: "Attendance reports" })).toBeVisible();

    await context.clearCookies({ name: "luma-access" });
    await page.goto("/sign-in");
    await expect(page).toHaveURL(/\/staff$/, { timeout: 10000 });
  });
});
