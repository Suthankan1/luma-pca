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

test.describe("Student search", () => {
  // The mock backend is shared across projects and specs: names are unique per project.
  let p = "qd";
  test.beforeEach(({}, testInfo) => {
    p = testInfo.project.name === "mobile" ? "qm" : "qd";
  });

  test("An administrator finds students by name, number or email, keeping filters", async ({ page }) => {
    await signInAsAdmin(page);
    const family = `Zed${p}fam`;
    await page.goto("/staff/students/import");
    await page.locator("#import-file").setInputFiles({ name: "s.csv", mimeType: "text/csv", buffer: Buffer.from(
      `number,name,email\n${p}-s1,Nimal ${family},${p}.s1@example.invalid\n${p}-s2,Kamala ${family},${p}.s2@example.invalid\n${p}-s3,Other Person,${p}.s3@example.invalid\n`) });
    await page.getByRole("button", { name: "Check file" }).click();
    await page.getByRole("button", { name: "Import 3 students" }).click();
    await expect(page.locator("#import-feedback")).toContainText("Imported 3 students");

    await page.goto("/staff/students");
    const search = page.getByRole("search");
    const rows = page.locator(".dir-list .dir-course");
    await search.getByLabel("Find a student").fill(family.toUpperCase());
    await search.getByRole("button", { name: "Search" }).click();
    await expect(page).toHaveURL(new RegExp(`\\?q=${family.toUpperCase()}$`));
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText(`Nimal ${family}`);
    await expect(page.getByText(`Showing 2 matching “${family.toUpperCase()}”, by student number.`)).toBeVisible();

    await search.getByLabel("Find a student").fill(`${p}.s3@example.invalid`);
    await search.getByRole("button", { name: "Search" }).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("Other Person");

    // The status filter keeps the search; nothing inactive matches.
    await search.getByLabel("Find a student").fill(`${p}-s`);
    await search.getByRole("button", { name: "Search" }).click();
    await expect(rows).toHaveCount(3);
    await page.getByRole("link", { name: "Inactive" }).click();
    await expect(page).toHaveURL(new RegExp(`q=${p}-s.*status=inactive|status=inactive.*q=${p}-s`));
    await expect(page.getByText(`No students match “${p}-s”.`)).toBeVisible();
    await expect(search.getByLabel("Find a student")).toHaveValue(`${p}-s`);
    await search.getByRole("link", { name: "Clear" }).click();
    await expect(page).toHaveURL(/\/staff\/students\?status=inactive$/);
  });
});
