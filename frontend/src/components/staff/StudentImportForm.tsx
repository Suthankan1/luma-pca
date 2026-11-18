"use client";

import Link from "next/link";
import { useEffect, useRef, useState, useTransition } from "react";
import { importStudents, type ImportOutcome } from "@/app/staff/students/actions";
import { parseStudentCsv, type ImportRow } from "@/lib/student-import";

const fieldNames = { studentNumber: "Student number", fullName: "Full name", email: "Email" } as const;

/**
 * The file is read in the browser and sent as rows; the API checks every row. "Check file"
 * writes nothing; "Import" is offered only after a clean check of the same file, batch and date.
 */
export function StudentImportForm({ batches, today }: { batches: { id: string; label: string }[]; today: string }) {
  const [file, setFile] = useState<{ name: string; rows: ImportRow[]; lines: number[] } | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [batchId, setBatchId] = useState("");
  const [enrolledOn, setEnrolledOn] = useState(today);
  const [outcome, setOutcome] = useState<(ImportOutcome & { key: string; dryRun: boolean }) | null>(null);
  const [pending, startTransition] = useTransition();
  const key = file ? `${file.name}|${file.rows.length}|${batchId}|${batchId ? enrolledOn : ""}` : "";
  const checkedClean = outcome?.ok && outcome.dryRun && outcome.problems.length === 0 && outcome.key === key;
  const batchLabel = batches.find(batch => batch.id === batchId)?.label;

  const input = useRef<HTMLInputElement>(null);
  // A file chosen before the page finished loading (a slow phone) fired its change event before React listened: pick it up.
  useEffect(() => {
    const early = input.current?.files?.[0];
    if (early) void choose(early);
  }, []);

  async function choose(chosen: File | undefined) {
    setOutcome(null);
    setFile(null);
    setFileError(null);
    if (!chosen) return;
    const parsed = parseStudentCsv(await chosen.text());
    if ("error" in parsed) setFileError(parsed.error);
    else setFile({ name: chosen.name, ...parsed });
  }

  function send(dryRun: boolean) {
    if (!file) return;
    const sentKey = key;
    startTransition(async () => {
      const result = await importStudents({ rows: file.rows, batchId, enrolledOn: batchId ? enrolledOn : null, dryRun });
      setOutcome({ ...result, key: sentKey, dryRun });
    });
  }

  if (outcome?.ok && outcome.imported) {
    return <div className="session-form staff-form">
      <p id="import-feedback" className="form-ok" role="status">
        Imported {outcome.rows} student{outcome.rows === 1 ? "" : "s"}{outcome.enrolled ? ` and enrolled them in ${batchLabel ?? "the batch"}` : ""}.
        Their sign-ins wait for an invitation, sent from each student’s record.
      </p>
      <p><Link className="text-link" href="/staff/students">Go to the student directory →</Link></p>
    </div>;
  }

  return <form className="session-form staff-form" onSubmit={event => { event.preventDefault(); send(!checkedClean); }}
    aria-describedby="import-feedback">
    <label htmlFor="import-file">CSV file</label>
    <input id="import-file" ref={input} type="file" accept=".csv,text/csv" required onChange={event => choose(event.target.files?.[0])} />
    <p className="panel-sub">Save the spreadsheet as CSV. The first line names the columns: student number, full name, email (other columns are ignored). Up to 1,000 students per file.</p>
    {fileError && <p id="import-feedback" className="session-error" role="alert">{fileError}</p>}
    {file && <p className="panel-sub">{file.name}: {file.rows.length} student{file.rows.length === 1 ? "" : "s"} found.</p>}
    <label htmlFor="import-batch">Enrol everyone in</label>
    <select id="import-batch" value={batchId} onChange={event => setBatchId(event.target.value)}>
      <option value="">No batch (enrol later)</option>
      {batches.map(batch => <option key={batch.id} value={batch.id}>{batch.label}</option>)}
    </select>
    {batchId && <>
      <label htmlFor="import-date">Enrolled on</label>
      <input id="import-date" type="date" required value={enrolledOn} onChange={event => setEnrolledOn(event.target.value)} />
    </>}
    <button className="action" type="submit" disabled={!file || pending}>
      {pending ? (checkedClean ? "Importing…" : "Checking…") : checkedClean ? `Import ${file!.rows.length} student${file!.rows.length === 1 ? "" : "s"}` : "Check file"}
    </button>
    {outcome && !outcome.ok && <p id="import-feedback" className="session-error" role="alert">{outcome.message}</p>}
    {checkedClean && <p id="import-feedback" className="form-ok" role="status">
      All {outcome.rows} rows are ready. Nothing has been saved yet.
    </p>}
    {outcome?.ok && outcome.problems.length > 0 && file && <div id="import-feedback" role="alert">
      <p className="session-error">Nothing was saved. Fix {outcome.problems.length === 1 ? "this problem" : `these ${outcome.problems.length} problems`} in the file and choose it again:</p>
      <ul className="dir-list import-problems" aria-label="Problems in the file">
        {outcome.problems.slice(0, 200).map(problem => <li key={`${problem.row}-${problem.field}`} className="dir-row">
          <span><strong>Line {file.lines[problem.row - 1] ?? problem.row}</strong> · {fieldNames[problem.field] ?? problem.field}: {problem.message}</span>
        </li>)}
      </ul>
      {outcome.problems.length > 200 && <p className="panel-sub">…and {outcome.problems.length - 200} more.</p>}
    </div>}
  </form>;
}
