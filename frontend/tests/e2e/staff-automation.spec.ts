import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Less manual work for staff", () => {
  let p = "ad";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "am" : "ad") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("Upcoming Zoom webinars become classes in one step, never twice", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    const C = `IMP-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`imp-${p}`);
    await page.locator("#course-title").fill("Import course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Import course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();

    await page.goto("/staff/sessions");
    await page.getByRole("link", { name: "Import classes from your Zoom webinars →" }).click();
    await expect(page).toHaveURL(/\/staff\/zoom\/import$/);
    const list = page.getByRole("region", { name: "UPCOMING WEBINARS" });
    await expect(list.getByRole("listitem", { name: "Webinar 844 0000 0001" })).toContainText("Not in LUMA yet");
    await expect(list.getByRole("listitem", { name: "Webinar 844 0000 0002" })).toContainText("no fixed time");
    await page.getByRole("search", { name: "Find a webinar" }).getByLabel("Find a webinar").fill("office");
    await page.getByRole("search", { name: "Find a webinar" }).getByRole("button", { name: "Search" }).click();
    await expect(list.getByRole("listitem", { name: /^Webinar / })).toHaveCount(1);
    await list.getByRole("link", { name: "Office hours" }).click();
    const office = page.getByRole("region", { name: "Webinar 844 0000 0002" });
    await expect(office).toContainText("no fixed time");
    await expect(office).toContainText("not on the server’s webhook allow-list");
    await page.getByRole("link", { name: "← All upcoming webinars" }).click();
    await list.getByRole("link", { name: "A/L Physics weekly" }).click();
    const weekly = page.getByRole("region", { name: "Webinar 844 0000 0001" });
    await expect(weekly).toContainText("recurring");
    await expect(weekly).not.toContainText("allow-list");

    await weekly.getByRole("button", { name: "Create classes" }).click();
    await expect(weekly.locator(".import-feedback")).toHaveText("Choose the batch or batches these classes are for.");
    await weekly.getByLabel("Mon 15 Feb 2027 · 18:00–20:00").uncheck();
    await weekly.getByLabel(`${C} · A · Batch A`).check();
    await weekly.getByLabel("Class title").fill("A/L Physics (imported)");
    await weekly.getByRole("button", { name: "Create classes" }).click();
    await expect(weekly.locator(".import-feedback")).toHaveText("Created 2 classes.");
    await expect(weekly.getByLabel("Mon 1 Feb 2027 · 18:00–20:00 · already a class")).toBeDisabled();
    await expect(weekly.getByLabel("Mon 15 Feb 2027 · 18:00–20:00")).toBeEnabled();

    await page.goto("/staff/zoom/import");
    await expect(page.getByRole("region", { name: "UPCOMING WEBINARS" }).getByRole("listitem", { name: "Webinar 844 0000 0001" }))
      .toContainText("2 classes in LUMA");
    await page.goto("/staff/sessions?from=2027-02-01");
    await expect(page.getByRole("link", { name: /A\/L Physics \(imported\)/ })).toHaveCount(2);
    await page.goto("/staff/activity?category=zoom");
    await expect(page.getByText("Imported 2 classes from Zoom webinar 84400000001").first()).toBeVisible();
  });

  test("Ended classes wait in one list and are finalised together; the owner can switch on automatic finalisation", async ({ page }) => {
    await signInStaff(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance");
    const rule = page.getByRole("region", { name: "ATTENDANCE RULE" });
    await page.locator("#policy-percent").fill("75");
    await page.locator("#policy-late").fill("10");
    await page.locator("#policy-description").fill("Approved at the staff meeting");
    await rule.getByLabel(/Finalise each class automatically/).check();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");
    await page.reload();
    await expect(rule.getByLabel(/Finalise each class automatically/)).toBeChecked();
    await expect(page.getByRole("region", { name: "WAITING TO BE FINALISED" })).toContainText("Automatic finalisation is on");
    await rule.getByLabel(/Finalise each class automatically/).uncheck();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");

    // A class that ended yesterday, with a webinar ID, waits for finalisation.
    await signInStaff(page, "admin-enrolled@example.invalid");
    const yesterday = new Date(Date.now() - 86_400_000 + 5.5 * 3_600_000).toISOString().slice(0, 10);
    const C = `FIN-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`fin-${p}`);
    await page.locator("#course-title").fill("Finalise course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Finalise course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2026-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await page.goto("/staff/sessions");
    await page.locator("#schedule-title").fill(`Ended class ${p}`);
    await page.locator("#schedule-date").fill(yesterday);
    await page.locator("#schedule-start").fill("09:00");
    await page.locator("#schedule-end").fill("10:00");
    await page.locator("form", { has: page.locator("#schedule-title") }).getByLabel(`${C} · A · Batch A`, { exact: true }).check();
    await page.locator("#schedule-webinar").fill("838 1234 5678");
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText(`Ended class ${p} scheduled.`);

    await page.goto("/staff/attendance");
    const waiting = page.getByRole("region", { name: "WAITING TO BE FINALISED" });
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toContainText("Ready to finalise");
    await waiting.getByRole("button", { name: /Finalise \d+ ready class/ }).click();
    await expect(page.locator("#finalise-all-feedback")).toContainText("Finalised");
    await page.reload();
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toHaveCount(0);
  });
});
