import { test, expect, type Page } from "@playwright/test";

/** Owners and administrators sign in with email and password. */
async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Staff courses and batches", () => {
  // The mock backend is shared by both browser projects, so each project uses its own codes.
  let suffix = "D";
  test.beforeEach(({}, testInfo) => {
    suffix = testInfo.project.name === "mobile" ? "M" : "D";
  });

  test("An administrator creates a course and a batch, sees refusals inline, and deactivates and reactivates", async ({ page }) => {
    await signInAsAdmin(page);
    await page.getByRole("link", { name: /Courses & batches/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/courses$/);
    await expect(page.getByRole("heading", { level: 1, name: "Courses & batches" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Courses & batches/ }).first()).toHaveAttribute("aria-current", "page");

    const course = `phy-${suffix}`;
    await page.getByLabel("Code", { exact: true }).first().fill(course);
    await page.getByLabel("Title").fill("A/L Physics 2027");
    await page.getByRole("button", { name: "Create course" }).click();
    await expect(page.locator("#course-feedback")).toHaveText(`Course ${course.toUpperCase()} created.`);
    await expect(page.getByLabel("Title")).toHaveValue("", { timeout: 2000 });
    const courseRow = page.locator(".dir-course").filter({ hasText: course.toUpperCase() });
    await expect(courseRow).toContainText("A/L Physics 2027");
    await expect(courseRow).toContainText("No batches yet.");

    await page.getByLabel("Code", { exact: true }).first().fill(course);
    await page.getByLabel("Title").fill("Again");
    await page.getByRole("button", { name: "Create course" }).click();
    await expect(page.locator("#course-feedback")).toHaveText("A course with this code already exists");
    await expect(page.locator("#course-feedback")).toHaveAttribute("role", "alert");
    await expect(page.getByLabel("Title")).toHaveValue("Again", { timeout: 2000 });

    await page.getByLabel("Course", { exact: true }).selectOption({ label: `${course.toUpperCase()} · A/L Physics 2027` });
    await page.locator("#batch-code").fill("mon");
    await page.getByLabel("Name").fill("Monday evening");
    await page.getByLabel("Starts").fill("2027-01-10");
    await page.getByLabel("Ends (optional)").fill("2027-01-09");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("A batch cannot end before it starts.");
    await expect(page.locator("#batch-code")).toHaveValue("mon", { timeout: 2000 });
    await expect(page.getByLabel("Name")).toHaveValue("Monday evening");
    await expect(page.getByLabel("Starts")).toHaveValue("2027-01-10");
    await page.getByLabel("Ends (optional)").fill("");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch MON created.");
    await expect(courseRow.locator(".dir-batch")).toContainText("MON Monday evening");
    await expect(courseRow.locator(".dir-batch")).toContainText("10 Jan 2027 onwards");

    await courseRow.getByRole("button", { name: `Deactivate course ${course.toUpperCase()}` }).click();
    await expect(courseRow.locator(".dir-row").first()).toContainText("Inactive");
    await expect(page.getByLabel("Course", { exact: true }).locator("option", { hasText: course.toUpperCase() })).toHaveCount(0);
    await courseRow.getByRole("button", { name: `Reactivate course ${course.toUpperCase()}` }).click();
    await expect(courseRow.locator(".dir-row").first()).toContainText("Active");
  });

  test("Signed-out visitors are sent to sign-in and the layout fits a phone", async ({ page }, testInfo) => {
    await page.goto("/staff/courses");
    await expect(page).toHaveURL(/\/sign-in/);
    if (testInfo.project.name !== "mobile") return;
    await signInAsAdmin(page);
    await page.goto("/staff/courses");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await expect(page.getByRole("button", { name: "Create course" })).toBeVisible();
  });
});
