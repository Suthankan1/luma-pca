/**
 * Reads a spreadsheet saved as CSV into student rows for the bulk import API. The first
 * line names the columns (any order, any letter case): student number, full name, email.
 * Quoted fields may contain commas, quotes ("") and line breaks. Blank lines are skipped.
 * The API checks every value; this only finds the columns and reports file-level problems.
 */
export const MAX_IMPORT_ROWS = 1000;
export const MAX_IMPORT_BYTES = 512 * 1024;

export type ImportRow = { studentNumber: string; fullName: string; email: string };
export type ParsedImport = { rows: ImportRow[]; lines: number[] } | { error: string };

const HEADERS: Record<string, keyof ImportRow> = {
  studentnumber: "studentNumber", number: "studentNumber", studentid: "studentNumber", indexnumber: "studentNumber",
  fullname: "fullName", name: "fullName", studentname: "fullName",
  email: "email", emailaddress: "email",
};

/** Splits CSV text into records, each with the 1-based line it starts on. */
export function csvRecords(text: string): { fields: string[]; line: number }[] {
  const records: { fields: string[]; line: number }[] = [];
  let fields: string[] = [];
  let field = "";
  let quoted = false;
  let line = 1;
  let start = 1;
  const input = text.replace(/^﻿/, "");
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (quoted) {
      if (c === "\"" && input[i + 1] === "\"") { field += "\""; i++; }
      else if (c === "\"") quoted = false;
      else { if (c === "\n") line++; field += c; }
    } else if (c === "\"" && field === "") quoted = true;
    else if (c === ",") { fields.push(field); field = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && input[i + 1] === "\n") i++;
      fields.push(field);
      records.push({ fields, line: start });
      fields = []; field = ""; line++; start = line;
    } else field += c;
  }
  if (field !== "" || fields.length > 0) { fields.push(field); records.push({ fields, line: start }); }
  return records.filter(record => record.fields.some(value => value.trim() !== ""));
}

export function parseStudentCsv(text: string): ParsedImport {
  if (new TextEncoder().encode(text).length > MAX_IMPORT_BYTES) return { error: "The file is larger than 512 KB. Split it into smaller files." };
  const [header, ...data] = csvRecords(text);
  if (!header) return { error: "The file is empty." };
  const columns = header.fields.map(name => HEADERS[name.toLowerCase().replace(/[^a-z]/g, "")]);
  const missing = (["studentNumber", "fullName", "email"] as const).filter(key => !columns.includes(key));
  if (missing.length) {
    return { error: "The first line must name the columns: student number, full name and email. Missing: "
      + missing.map(key => ({ studentNumber: "student number", fullName: "full name", email: "email" })[key]).join(", ") + "." };
  }
  if (data.length === 0) return { error: "The file has column names but no students." };
  if (data.length > MAX_IMPORT_ROWS) return { error: `The file has ${data.length} students; import at most ${MAX_IMPORT_ROWS} at a time.` };
  const rows = data.map(record => {
    const row: ImportRow = { studentNumber: "", fullName: "", email: "" };
    columns.forEach((key, index) => { if (key && !row[key]) row[key] = (record.fields[index] ?? "").trim(); });
    return row;
  });
  return { rows, lines: data.map(record => record.line) };
}

/** Rows sent to the server action, re-checked there: an array of up to 1000 rows of three short strings. */
export function validImportRows(value: unknown): ImportRow[] | null {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_IMPORT_ROWS) return null;
  const ok = value.every(row => row && typeof row === "object"
    && (["studentNumber", "fullName", "email"] as const).every(key => typeof row[key] === "string" && row[key].length <= 400));
  return ok ? value.map(row => ({ studentNumber: row.studentNumber, fullName: row.fullName, email: row.email })) : null;
}
