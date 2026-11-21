import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

const csv = (name: string, text: string) => ({ name, mimeType: "text/csv", buffer: Buffer.from(text) });

test.describe("Student import from CSV", () => {
  // The mock backend is shared across projects and specs: codes are unique per project.
  let p = "id";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "im" : "id") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An administrator checks a file, fixes it and imports students into a batch", async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-phy`);
    await page.locator("#course-title").fill("Import course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${p.toUpperCase()}-PHY · Import course` });
    await page.locator("#batch-code").fill(`${p}-mon`);
    await page.locator("#batch-name").fill("Monday import");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${p.toUpperCase()}-MON created.`);

    await page.goto("/staff/students/manage");
    await page.getByRole("link", { name: "Import many students from a CSV file →" }).click();
    await expect(page).toHaveURL(/\/staff\/students\/import$/);
    const file = page.locator("#import-file");

    // A file without the needed columns is refused in the browser.
    await file.setInputFiles(csv("wrong.csv", "name,phone\nAnn,077\n"));
    await expect(page.locator("#import-feedback")).toHaveText(/Missing: student number, email\.$/);

    // Problems come back with the file's own line numbers; nothing is saved.
    await file.setInputFiles(csv("students.csv",
      `Student number,Full name,Email\n${p}-001,"Perera, Nimal",${p}.1@example.invalid\n\n${p}-001,Repeat,${p}.2@example.invalid\n${p}-003,,bad-email\n`));
    await expect(page.getByText("students.csv: 3 students found.")).toBeVisible();
    await page.getByRole("button", { name: "Check file" }).click();
    const problems = page.getByRole("list", { name: "Problems in the file" });
    await expect(problems.getByRole("listitem")).toHaveText([
      "Line 4 · Student number: Same student number as row 1",
      "Line 5 · Email: Email is not a valid address",
      "Line 5 · Full name: A full name of up to 200 characters is required",
    ]);
    await expect(page.locator("#import-feedback")).toContainText("Nothing was saved. Fix these 3 problems");

    // The fixed file checks clean; only then is the import offered.
    await file.setInputFiles(csv("fixed.csv",
      `Student number,Full name,Email\n${p}-001,"Perera, Nimal",${p}.1@example.invalid\n${p}-002,Repeat Fixed,${p}.2@example.invalid\n`));
    await page.locator("#import-batch").selectOption({ label: `${p.toUpperCase()}-PHY · ${p.toUpperCase()}-MON · Monday import` });
    await page.locator("#import-date").fill("2027-01-12");
    await page.getByRole("button", { name: "Check file" }).click();
    await expect(page.locator("#import-feedback")).toHaveText("All 2 rows are ready. Nothing has been saved yet.");
    // Changing the batch or date needs a new check.
    await page.locator("#import-date").fill("2027-01-13");
    await expect(page.getByRole("button", { name: "Check file" })).toBeVisible();
    await page.getByRole("button", { name: "Check file" }).click();
    await page.getByRole("button", { name: "Import 2 students" }).click();
    await expect(page.locator("#import-feedback")).toHaveText(
      `Imported 2 students and enrolled them in ${p.toUpperCase()}-PHY · ${p.toUpperCase()}-MON · Monday import. Their sign-ins wait for an invitation, sent from each student’s record.`);

    await page.getByRole("link", { name: "Go to the student directory →" }).click();
    await page.getByRole("searchbox", { name: "Search students" }).fill(`${p}-001`);
    await page.getByRole("link", { name: /Perera, Nimal/ }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Perera, Nimal");

    await page.goto(`/staff/students/manage?q=${p}-001`);
    await page.getByRole("link", { name: `${p.toUpperCase()}-001 Perera, Nimal` }).click();
    await expect(page.locator(".dir-course").filter({ hasText: `${p.toUpperCase()}-MON` })).toContainText("13 Jan 2027");

    // Importing the same file again reports every row as already existing.
    await page.goto("/staff/students/import");
    await file.setInputFiles(csv("fixed.csv",
      `Student number,Full name,Email\n${p}-001,"Perera, Nimal",${p}.1@example.invalid\n`));
    await page.getByRole("button", { name: "Check file" }).click();
    await expect(problems.getByRole("listitem")).toHaveText([
      "Line 2 · Email: An account with this email already exists",
      "Line 2 · Student number: A student with this student number already exists",
    ]);
  });
});
