import { expect, test, type Page } from "@playwright/test";

async function signIn(page: Page, email: string) {
  await page.goto("/sign-in?portal=student");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
}

test.describe("Student portal", () => {
  test("A student sees their upcoming classes and batches", async ({ page }) => {
    await signIn(page, "student@example.invalid");
    await page.getByRole("link", { name: "My classes" }).click();
    await expect(page.getByRole("heading", { name: "My classes", level: 1 })).toBeVisible();

    const classes = page.getByRole("region", { name: "MY CLASSES" });
    const waves = classes.getByRole("listitem", { name: "Waves and oscillations" });
    await expect(waves).toContainText(/\d{1,2} \w{3} \d{4} · 18:00–20:00 \(Asia\/Colombo\)/);
    await expect(waves).toContainText("MON");
    await expect(waves.locator(".status")).toHaveText("Scheduled");
    const revision = classes.getByRole("listitem", { name: "Revision for both batches" });
    await expect(revision).toContainText("MON, SAT");
    await expect(revision.locator(".status")).toHaveText("Cancelled");
    // Only the four weeks shown; the next page has the later class.
    await expect(classes).not.toContainText("Far future class");
    await classes.getByRole("link", { name: "Next four weeks →" }).click();
    await expect(page).toHaveURL(/\/student\/classes\?from=\d{4}-\d{2}-\d{2}$/);
    await expect(classes).toContainText("Far future class");
    await expect(classes).not.toContainText("Waves and oscillations");
    await classes.getByRole("link", { name: "From today" }).click();
    await expect(page).toHaveURL(/\/student\/classes$/);
    await expect(classes).toContainText("Waves and oscillations");

    const batches = page.getByRole("region", { name: "MY BATCHES" });
    await expect(batches.getByRole("listitem").filter({ hasText: "MON" })).toContainText("from 5 Jan 2026");
    await expect(batches.getByRole("listitem").filter({ hasText: "MON" }).locator(".status")).toHaveText("Enrolled");
    await expect(batches.getByRole("listitem").filter({ hasText: "SAT" })).toContainText("withdrawn 31 Mar 2026");
    await expect(batches.getByRole("listitem").filter({ hasText: "SAT" }).locator(".status")).toHaveText("Withdrawn");

    // A malformed date in the address falls back to today.
    await page.goto("/student/classes?from=2026-02-30");
    await expect(classes).toContainText("Waves and oscillations");
  });

  test("The class running now is shown first with a Zoom join button", async ({ page }) => {
    await signIn(page, "student@example.invalid");
    await page.getByRole("link", { name: "My classes" }).click();
    const next = page.getByRole("region", { name: "Live now" });
    await expect(next).toContainText("Live class now");
    await expect(next.getByRole("link", { name: /Join on Zoom now/ })).toHaveAttribute("href", "https://zoom.us/j/81234567890");
    await expect(page.getByRole("list", { name: "Last 30 days" }).getByRole("listitem", { name: /^Attendance: \d+%$/ })).toBeVisible();
    const classes = page.getByRole("region", { name: "MY CLASSES" });
    await expect(classes.getByRole("listitem", { name: "Live class now" })).toContainText("Live now");
    await expect(classes.getByRole("listitem", { name: "Revision for both batches" }).getByRole("link", { name: /Zoom link/ })).toHaveCount(0);
    await page.getByRole("complementary", { name: "Academy navigation" }).getByRole("link", { name: "My attendance" }).click();
    await expect(page.getByRole("region", { name: "Class history" })).toBeVisible();
  });

  test("The home page offers a student and a staff way in", async ({ page }) => {
    await page.context().clearCookies();
    await page.goto("/");
    await page.getByRole("link", { name: /Student sign in/ }).click();
    await expect(page).toHaveURL(/\/sign-in\?portal=student$/);
  });

  test("A student without enrolments is told how to get one", async ({ page }) => {
    await signIn(page, "new-student@example.invalid");
    await page.getByRole("link", { name: "My classes" }).click();
    await expect(page.getByRole("region", { name: "MY CLASSES" })).toContainText("No classes in these four weeks.");
    await expect(page.getByRole("region", { name: "MY BATCHES" })).toContainText("You aren’t enrolled in a batch yet.");
  });
});
