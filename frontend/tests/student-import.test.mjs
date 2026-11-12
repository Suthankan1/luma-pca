import assert from "node:assert/strict";
import test from "node:test";
import { csvRecords, MAX_IMPORT_ROWS, parseStudentCsv, validImportRows } from "../src/lib/student-import.ts";

test("reads quoted fields, escaped quotes, CRLF, a BOM and line breaks inside quotes", () => {
  const text = "﻿Number,Name,Email\r\nPCA-1,\"Perera, Nimal\",n@example.invalid\r\n\r\nPCA-2,\"Said \"\"Sam\"\"\nSmith\",s@example.invalid\nPCA-3,Last,l@example.invalid";
  assert.deepEqual(csvRecords(text), [
    { fields: ["Number", "Name", "Email"], line: 1 },
    { fields: ["PCA-1", "Perera, Nimal", "n@example.invalid"], line: 2 },
    { fields: ["PCA-2", "Said \"Sam\"\nSmith", "s@example.invalid"], line: 4 },
    { fields: ["PCA-3", "Last", "l@example.invalid"], line: 6 },
  ]);
});

test("finds the columns by name in any order and reports each row's line", () => {
  const parsed = parseStudentCsv("Email, Full Name ,Student Number,Phone\n a@example.invalid ,Ann,PCA-1,077\n\nb@example.invalid,Bob,PCA-2,\n");
  assert.deepEqual(parsed, {
    rows: [
      { studentNumber: "PCA-1", fullName: "Ann", email: "a@example.invalid" },
      { studentNumber: "PCA-2", fullName: "Bob", email: "b@example.invalid" },
    ],
    lines: [2, 4],
  });
});

test("short rows give empty values for the API to report", () => {
  assert.deepEqual(parseStudentCsv("student number,full name,email\nPCA-1").rows,
    [{ studentNumber: "PCA-1", fullName: "", email: "" }]);
});

test("file-level problems", () => {
  assert.deepEqual(parseStudentCsv(""), { error: "The file is empty." });
  assert.match(parseStudentCsv("number,email\nPCA-1,a@example.invalid").error, /Missing: full name\.$/);
  assert.deepEqual(parseStudentCsv("number,name,email\n"), { error: "The file has column names but no students." });
  const many = "number,name,email\n" + Array.from({ length: MAX_IMPORT_ROWS + 1 }, (_, i) => `P${i},N,e${i}@example.invalid`).join("\n");
  assert.match(parseStudentCsv(many).error, /1001 students; import at most 1000/);
  assert.match(parseStudentCsv("number,name,email\n" + "x".repeat(600 * 1024)).error, /larger than 512 KB/);
});

test("rows from the browser are re-checked before being sent", () => {
  const row = { studentNumber: "PCA-1", fullName: "Ann", email: "a@example.invalid" };
  assert.deepEqual(validImportRows([{ ...row, extra: "dropped" }]), [row]);
  assert.equal(validImportRows([]), null);
  assert.equal(validImportRows("rows"), null);
  assert.equal(validImportRows([{ ...row, email: 5 }]), null);
  assert.equal(validImportRows([{ ...row, fullName: "x".repeat(401) }]), null);
  assert.equal(validImportRows(Array(MAX_IMPORT_ROWS + 1).fill(row)), null);
});
