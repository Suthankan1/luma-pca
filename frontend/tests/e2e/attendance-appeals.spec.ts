import { test, expect, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Attendance appeals", () => {
  // One mock student record is shared by both projects: each project appeals it once and an administrator decides.
  let accepted = "Excused";
  test.beforeEach(({}, testInfo) => {
    accepted = testInfo.project.name === "mobile" ? "Present" : "Excused";
  });

  test("The student report hides appeals and staff can still resolve an existing appeal", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student$/);
    await page.goto("/student?month=2026-11");
    await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
    await expect(page.getByText("Attendance appeals", { exact: true })).toHaveCount(0);
    const headers = { Authorization: "Bearer fake-access-token.for-bff.e2e-test" };
    const api = mockApiBase() + "/api/v1/me/attendance";
    const [record] = await (await page.request.get(api, { headers })).json();
    const seeded = await page.request.post(`${api}/${record.id}/appeals`,
      { headers, data: { message: "I joined from my parent's phone with their Zoom account." } });
    expect(seeded.status()).toBe(201);

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

  });
});
