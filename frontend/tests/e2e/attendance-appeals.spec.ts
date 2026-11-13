import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Attendance appeals", () => {
  // One mock student record is shared by both projects: each project appeals it once and an administrator decides.
  let accepted = "Excused";
  test.beforeEach(({}, testInfo) => {
    accepted = testInfo.project.name === "mobile" ? "Present" : "Excused";
  });

  test("A student appeals final attendance and an administrator accepts it, correcting the record", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student$/);
    const record = page.getByRole("region", { name: "MY ATTENDANCE" }).getByRole("listitem", { name: "Attendance Waves and oscillations" });
    await record.getByText("Appeal this").click();
    await record.getByLabel("What should the academy know?").fill("short");
    await record.getByRole("button", { name: "Send appeal" }).click();
    await expect(record.locator(".session-error")).toHaveText("Explain your appeal in 10 to 1000 characters.");
    await record.getByLabel("What should the academy know?").fill("I joined from my parent's phone with their Zoom account.");
    await record.getByRole("button", { name: "Send appeal" }).click();
    await expect(record).toContainText("appeal waiting for a decision");
    await expect(record.getByText("Appeal this")).toHaveCount(0);

    await page.context().clearCookies();
    await signInAsAdmin(page);
    await page.goto("/staff/attendance");
    const appeal = page.getByRole("region", { name: "APPEALS" }).getByRole("listitem", { name: "Appeal PCA-STUDENT Waves and oscillations" });
    await expect(appeal).toContainText("I joined from my parent's phone");
    await appeal.getByLabel("Outcome if accepted").selectOption({ label: accepted });
    await appeal.getByLabel("Note to the student").fill("Confirmed in Zoom review");
    await appeal.getByRole("button", { name: "Accept" }).click();
    await expect(appeal).toHaveCount(0);
    await page.goto("/staff/activity?category=classes");
    await expect(page.getByText("Attendance appeal accepted").first()).toBeVisible();

    await page.context().clearCookies();
    await page.goto("/sign-in");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(record.locator(".status")).toHaveText(accepted);
    await expect(record).toContainText("appeal accepted");
  });
});
