import { randomUUID } from "node:crypto";
import ExcelJS from "exceljs";
import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function seed(page: Page, path: string, data: object) {
  const response = await page.request.post(`${mockApiBase()}${path}`, {
    data, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" },
  });
  expect(response.ok(), `Seed ${path}: HTTP ${response.status()}`).toBe(true);
  return response.json();
}

test("report options load on selection and each workbook still downloads", async ({ page }) => {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
  const suffix = randomUUID().slice(0, 8);
  const studentNumber = `PCA-RPT-${suffix.toUpperCase()}`;
  const studentName = `Fixture Attendee ${suffix}`;
  const course = await seed(page, "/api/v1/courses", { code: `RPT-${suffix}`, title: "Report course" });
  const batch = await seed(page, "/api/v1/batches", { courseId: course.id, code: "RPT", name: "Report class", startsOn: "2026-01-01" });
  await seed(page, "/api/v1/students", { fullName: studentName, studentNumber, email: `report-${suffix}@example.invalid` });
  const colombo = new Date(Date.now() + 19_800_000);
  const month = new Date(Date.UTC(colombo.getUTCFullYear(), colombo.getUTCMonth() - 1, 12)).toISOString().slice(0, 7);
  await seed(page, "/api/v1/class-sessions", { title: `Report class · Practice ${suffix}`, startsAt: `${month}-12T12:00:00Z`,
    endsAt: `${month}-12T13:00:00Z`, batchIds: [batch.id], timeZone: "Asia/Colombo" });

  await page.goto(`/staff/reports?month=${month}`);
  await expect(page.getByRole("navigation", { name: "Report types" }).getByRole("link")).toHaveCount(3);
  await expect(page.getByRole("searchbox")).toHaveCount(0);
  for (const [link, search, record, verify] of [
    ["Class session report", suffix, /Report class/, "Report class"],
    ["Class type report", suffix, /Report class/, "Report class"],
    ["Student report", studentNumber.replaceAll("-", ""), new RegExp(studentName), studentNumber],
  ] as const) {
    await page.getByRole("link", { name: new RegExp(link) }).click();
    const searchbox = page.getByRole("searchbox");
    // Wait for the controlled search to render a result before selecting a radio.
    await expect(async () => {
      await searchbox.fill("");
      await searchbox.fill("no such report option");
      await expect(page.getByText("No matching records.")).toBeVisible({ timeout: 500 });
    }).toPass({ timeout: 5000 });
    await searchbox.fill(search);
    await page.getByRole("radio", { name: record }).check();
    const event = page.waitForEvent("download");
    await page.getByRole("button", { name: "Download Excel" }).click();
    const download = await event;
    expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.readFile((await download.path())!);
    expect(JSON.stringify(workbook.getWorksheet("Summary")?.getSheetValues())).toContain(verify);
  }
});
