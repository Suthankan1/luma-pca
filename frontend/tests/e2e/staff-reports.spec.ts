import { randomUUID } from "node:crypto";
import ExcelJS from "exceljs";
import { readFile } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function seed(page: Page, path: string, data: object) {
  const response = await page.request.post(`${mockApiBase()}${path}`, {
    data, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" },
  });
  expect(response.ok(), `Seed ${path}: HTTP ${response.status()}`).toBe(true);
  return response.json();
}

test("all three staff reports download private, lightweight PDFs and Excel workbooks", async ({ page }) => {
  const exportPath = "/staff/reports/download";
  const anonymous = await page.request.get(`${exportPath}?format=pdf`, { maxRedirects: 0 });
  expect(anonymous.status()).toBe(307);
  expect(anonymous.headers().location).toContain("/sign-in?portal=staff");

  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
  expect((await page.request.get(`${exportPath}?kind=session&format=pdf&month=invalid&selection=invalid`)).status()).toBe(400);
  const suffix = randomUUID().slice(0, 8);
  const studentNumber = `PCA-RPT-${suffix.toUpperCase()}`;
  const studentName = `Fixture Attendee ${suffix}`;
  const course = await seed(page, "/api/v1/courses", { code: `RPT-${suffix}`, title: "Report course" });
  const batch = await seed(page, "/api/v1/batches", { courseId: course.id, code: "RPT", name: "Report class", startsOn: "2026-01-01" });
  const student = await seed(page, "/api/v1/students", { fullName: studentName, studentNumber, email: `report-${suffix}@example.invalid` });
  await seed(page, `/api/v1/batches/${batch.id}/enrollments`, { studentId: student.id, enrolledOn: "2026-01-01" });
  const colombo = new Date(Date.now() + 19_800_000);
  const month = new Date(Date.UTC(colombo.getUTCFullYear(), colombo.getUTCMonth() - 1, 12)).toISOString().slice(0, 7);
  await seed(page, "/api/v1/class-sessions", { title: `Report class - Practice ${suffix}`, startsAt: `${month}-12T12:00:00Z`,
    endsAt: `${month}-12T13:00:00Z`, batchIds: [batch.id], timeZone: "Asia/Colombo" });

  await page.goto(`/staff/reports?month=${month}`);
  await expect(page.getByRole("navigation", { name: "Report types" }).getByRole("link")).toHaveCount(3);
  await expect(page.getByRole("searchbox")).toHaveCount(0);
  for (const [kind, link, search, record, verify] of [
    ["session", "Class session report", suffix, /Report class/, "Report class"],
    ["class-type", "Class type report", suffix, /Report class/, "Report class"],
    ["student", "Student report", studentNumber.replaceAll("-", ""), new RegExp(studentName), studentNumber],
  ] as const) {
    await page.getByRole("link", { name: new RegExp(link) }).click();
    const searchbox = page.getByRole("searchbox");
    await expect(async () => {
      await searchbox.fill("");
      await searchbox.fill("no such report option");
      await expect(page.getByText("No matching records.")).toBeVisible({ timeout: 500 });
    }).toPass({ timeout: 5000 });
    await searchbox.fill(search);
    const option = page.getByRole("radio", { name: record });
    const pdfButton = page.getByRole("button", { name: "Download PDF" });
    await expect(pdfButton).toBeDisabled();
    await option.check();
    const pdfEvent = page.waitForEvent("download");
    await pdfButton.click();
    const pdf = await pdfEvent;
    expect(pdf.suggestedFilename()).toBe(`luma-${kind}-${month}.pdf`);
    const bytes = await readFile(await pdf.path());
    expect(bytes.subarray(0, 5).toString()).toBe("%PDF-");
    expect(bytes.toString("latin1")).toMatch(/\/Type\s*\/Page\b/);
    expect(bytes.byteLength).toBeLessThan(180_000);
    const query = new URLSearchParams({ format: "pdf", kind, month, selection: await option.inputValue() });
    const response = await page.request.get(`${exportPath}?${query}`);
    expect(response.status()).toBe(200);
    expect(response.headers()["content-type"]).toBe("application/pdf");
    expect(response.headers()["cache-control"]).toBe("private, no-store");
    expect(response.headers()["content-disposition"]).toContain(pdf.suggestedFilename());
    const event = page.waitForEvent("download");
    await page.getByRole("button", { name: "Download Excel" }).click();
    const download = await event;
    expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.readFile((await download.path())!);
    expect(JSON.stringify(workbook.getWorksheet("Summary")?.getSheetValues())).toContain(verify);
  }

  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", "student@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
  const studentQuery = new URLSearchParams({ format: "pdf", kind: "student", month, selection: student.id });
  const forbidden = await page.request.get(`${exportPath}?${studentQuery}`, { maxRedirects: 0 });
  expect(forbidden.status()).toBe(307);
  expect(forbidden.headers().location).toMatch(/\/student$/);
});
