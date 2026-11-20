import { expect, test, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in?portal=staff");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

// One shared mock backend: run in order, the first project only (the class is set up once).
test.describe.configure({ mode: "serial" });

test.describe("Academy classes", () => {
  test("A class from the Academy records is set up in one step and its students synced", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "Sets up shared mock data once");
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/academy");
    await expect(page).toHaveURL(/\/staff\/academy$/);
    await expect(page.getByRole("heading", { name: "Academy classes", level: 1 })).toBeVisible();

    const physics = page.getByRole("listitem", { name: "A/L Physics 2027" });
    await expect(physics).toContainText("3 students");
    await expect(physics).toContainText("Not set up in LUMA yet.");
    await expect(page.getByRole("listitem", { name: "Admission" })).toContainText("1 student");

    await physics.getByRole("button", { name: "Set up in LUMA" }).click();
    await expect(physics.locator(".form-ok")).toHaveText(
      "Synced: 3 students in the Academy class · 3 new to LUMA · 3 enrolled now.");
    await expect(physics.getByRole("list", { name: "Needs attention" })).toContainText("1 without a single name");
    await expect(physics.getByRole("list", { name: "Needs attention" })).toContainText("2 new without a usable email");

    await page.reload();
    await expect(physics).toContainText("Set up");
    await expect(physics).toContainText(/last synced \d{1,2} \w{3} \d{4}/);
    await physics.getByRole("button", { name: "Sync students of A/L Physics 2027" }).click();
    await expect(physics.locator(".form-ok")).toHaveText(
      "Synced: 3 students in the Academy class · 0 enrolled now · 3 already enrolled.");

    await physics.getByRole("link", { name: /ACADEMY · A-L-PHYSICS-2027/ }).click();
    const roster = page.getByRole("region", { name: "ROSTER" });
    await expect(roster).toContainText("3 enrolled");
    await expect(roster).toContainText("PCA101");
    await expect(roster).toContainText("Amaya Perera");
  });
});
