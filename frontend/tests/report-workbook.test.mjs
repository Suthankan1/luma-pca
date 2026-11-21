import assert from "node:assert/strict";
import test from "node:test";
import ExcelJS from "exceljs";
import { addReportSheet } from "../src/lib/report-workbook.ts";

test("Excel report sheets preserve typed values and store formula-like input as text", async () => {
  const original = new ExcelJS.Workbook();
  const untrustedName = '=HYPERLINK("https://example.invalid","Example")';
  addReportSheet(original, "Students", [
    { header: "Registration ID", width: 20 }, { header: "Student", width: 32 },
    { header: "Attendance", width: 16, percent: true },
  ], [["0001", untrustedName, 0.75], ["0002", "Student", null]]);
  const loaded = new ExcelJS.Workbook();
  await loaded.xlsx.load(await original.xlsx.writeBuffer());
  const sheet = loaded.getWorksheet("Students");
  assert.equal(sheet.getCell("A2").value, "0001");
  assert.equal(sheet.getCell("B2").value, untrustedName);
  assert.equal(sheet.getCell("B2").type, ExcelJS.ValueType.String);
  assert.equal(sheet.getCell("C2").value, 0.75);
  assert.equal(sheet.getCell("C2").numFmt, "0%");
  assert.equal(sheet.getCell("C3").value, null);
});
