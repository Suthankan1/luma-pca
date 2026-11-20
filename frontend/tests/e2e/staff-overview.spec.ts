import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Staff overview", () => {
  test("Managers see today's classes and what needs attention; other roles see their own entry points", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    await expect(page.getByRole("region", { name: "Today’s classes" })).toBeVisible();
    const tasks = page.getByRole("region", { name: "Needs attention" });
    await expect(tasks).toBeVisible();
    await expect(tasks.getByText("Failed Zoom events")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Home", exact: true })).toHaveAttribute("aria-current", "page");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
    await page.getByRole("link", { name: "View all classes" }).click();
    await expect(page).toHaveURL(/\/staff\/sessions$/);

    await signInStaff(page, "instructor@example.invalid");
    await expect(page.getByRole("region", { name: "Today’s classes" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Needs attention" })).toHaveCount(0);

    await signInStaff(page, "analyst@example.invalid");
    await page.getByRole("region", { name: "Attendance by batch" }).getByRole("link", { name: "Open reports" }).click();
    await expect(page.getByRole("region", { name: "ATTENDANCE BY BATCH" })).toBeVisible();
  });
});
