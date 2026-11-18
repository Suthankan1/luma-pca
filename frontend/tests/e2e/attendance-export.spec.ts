import { scheduleClass } from "./support/classes";
import { readFileSync } from "node:fs";
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

test.describe("Attendance export", () => {
  // The mock backend is shared across projects and specs: codes are unique per project.
  let p = "xd";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "xm" : "xd") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An administrator downloads a class's and a batch's attendance as CSV, and each export is logged", async ({ page }) => {
    await signInAsAdmin(page);
    const C = `EXP-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`exp-${p}`);
    await page.locator("#course-title").fill("Export course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Export course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");

    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Export class ${p}`, date: "2027-04-05", start: "14:00", end: "16:00", batch: `${C} · A · Batch A` });
    await page.goto("/staff/sessions?from=2027-04-05");
    await page.getByRole("link", { name: new RegExp(`Export class ${p}`) }).first().click();

    const attendance = page.getByRole("region", { name: "PROVISIONAL ATTENDANCE" });
    const classDownload = page.waitForEvent("download");
    await attendance.getByRole("link", { name: "Download CSV" }).click();
    const classFile = await classDownload;
    expect(classFile.suggestedFilename()).toBe("attendance-2027-04-05.csv");
    const path = await classFile.path();
    expect(readFileSync(path, "utf8")).toContain("student_number");

    // The batch export: a date range on the batch page, reached from the class's batch list.
    await page.getByRole("link", { name: "Roster →" }).first().click();
    const batchPage = page;
    const batchExport = batchPage.getByRole("region", { name: "ATTENDANCE EXPORT" });
    await batchExport.getByLabel("From").fill("2027-04-01");
    await batchExport.getByLabel("To").fill("2027-04-30");
    // The link stays on screen on a phone.
    const box = await batchExport.getByRole("link", { name: "Download CSV" }).boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(batchPage.viewportSize()!.width);
    const batchDownload = batchPage.waitForEvent("download");
    await batchExport.getByRole("link", { name: "Download CSV" }).click();
    expect((await batchDownload).suggestedFilename()).toBe("attendance-a-2027-04-01-to-2027-04-30.csv");

    await page.goto("/staff/activity?category=classes");
    await expect(page.getByText(`Exported attendance of “Export class ${p}”`).first()).toBeVisible();
    await expect(page.getByText("Exported attendance of batch A").first()).toBeVisible();
  });
});
