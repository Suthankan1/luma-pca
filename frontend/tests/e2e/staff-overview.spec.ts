import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Staff overview", () => {
  test("Managers see today's classes and what needs attention; other roles see their own entry points", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    await expect(page.getByRole("region", { name: "TODAY’S CLASSES" })).toBeVisible();
    const tasks = page.getByRole("region", { name: "NEEDS ATTENTION" });
    for (const label of ["Classes ready to finalise", "Ended classes waiting for Zoom", "Appeals waiting for a decision",
      "Zoom accounts to match", "Failed Zoom events"]) {
      await expect(tasks.getByRole("listitem", { name: label })).toContainText(/\d/);
    }
    await tasks.getByRole("link", { name: "Zoom accounts to match" }).click();
    await expect(page).toHaveURL(/\/staff\/zoom$/);
    await expect(page.getByText("COMING NEXT")).toHaveCount(0);

    await signInStaff(page, "instructor@example.invalid");
    await expect(page.getByRole("region", { name: "YOUR BATCHES" })).toBeVisible();
    await expect(page.getByRole("region", { name: "NEEDS ATTENTION" })).toHaveCount(0);

    await signInStaff(page, "analyst@example.invalid");
    await page.getByRole("region", { name: "REPORTS" }).getByRole("link", { name: "Attendance by batch →" }).click();
    await expect(page.getByRole("region", { name: "ATTENDANCE BY BATCH" })).toBeVisible();
  });
});
