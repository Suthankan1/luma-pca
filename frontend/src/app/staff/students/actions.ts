"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { validImportRows } from "@/lib/student-import";
import {
  isIsoDate, parseEnrollForm, parseStudentForm, problemMessage, submittedValues, uuidOrNull, type FormState,
} from "@/lib/staff-forms";

// Every action re-validates its input; the API re-checks the caller's role and academy on each call.

const studentFields = ["studentNumber", "fullName", "email"] as const;

export async function createStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, studentFields);
  const input = parseStudentForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi<{ id: string; studentNumber: string }>("/api/v1/students", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/students");
  revalidatePath("/staff/students/manage");
  return { ok: true, message: `Student ${result.data.studentNumber} added. Open the record to send an invitation.`,
    values: { link: `/staff/students/${result.data.id}` } };
}

export async function updateStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const version = Number(form.get("version"));
  const fullName = String(form.get("fullName") ?? "").trim();
  const active = form.get("active") === "true";
  if (!id || !Number.isSafeInteger(version) || version < 0) return { ok: false, message: "That request was not valid. Reload the page." };
  if (!fullName || fullName.length > 200) {
    return { ok: false, message: "Enter the student’s full name, up to 200 characters.", values: { fullName: fullName.slice(0, 300) } };
  }
  const result = await staffApi(`/api/v1/students/${id}`, { method: "PUT", body: { fullName, active, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values: { fullName } };
  revalidatePath(`/staff/students/${id}`);
  revalidatePath("/staff/students");
  revalidatePath("/staff/students/manage");
  return { ok: true, message: "Saved." };
}

export async function enroll(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const studentId = uuidOrNull(form.get("studentId"));
  const values = submittedValues(form, ["batchId", "enrolledOn"]);
  const input = parseEnrollForm(form);
  if (!studentId) return { ok: false, message: "That request was not valid. Reload the page." };
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi(`/api/v1/batches/${input.batchId}/enrollments`,
    { method: "POST", body: { studentId, enrolledOn: input.enrolledOn } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath(`/staff/students/${studentId}`);
  return { ok: true, message: "Enrolled." };
}

/** Withdraws on a date, or re-enrols when no withdrawal date is sent. */
export async function updateEnrollment(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const studentId = uuidOrNull(form.get("studentId"));
  const version = Number(form.get("version"));
  const enrolledOn = form.get("enrolledOn");
  const withdrawnOn = form.get("withdrawnOn") || null;
  if (!id || !studentId || !Number.isSafeInteger(version) || version < 0 || !isIsoDate(enrolledOn)
      || (withdrawnOn !== null && !isIsoDate(withdrawnOn))) {
    return { ok: false, message: "Enter a valid withdrawal date." };
  }
  if (withdrawnOn && withdrawnOn < enrolledOn) return { ok: false, message: "A withdrawal date cannot be before the enrolment date." };
  const result = await staffApi(`/api/v1/enrollments/${id}`, { method: "PUT", body: { enrolledOn, withdrawnOn, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/students/${studentId}`);
  return { ok: true, message: withdrawnOn ? "Withdrawn." : "Re-enrolled." };
}

export type ImportProblem = { row: number; field: "studentNumber" | "fullName" | "email"; message: string };
export type ImportOutcome = { ok: true; imported: boolean; rows: number; enrolled: number; problems: ImportProblem[] }
  | { ok: false; message: string };

/** Checks (dryRun) or imports rows read from a CSV file in the browser; nothing is written unless every row is valid. */
export async function importStudents(input: { rows: unknown; batchId: unknown; enrolledOn: unknown; dryRun: unknown }): Promise<ImportOutcome> {
  await requireSameOriginAction();
  const rows = validImportRows(input?.rows);
  const batchId = input?.batchId === "" || input?.batchId == null ? null : (typeof input.batchId === "string" ? uuidOrNull(input.batchId) : null);
  const enrolledOn = batchId ? input?.enrolledOn : null;
  if (!rows || (input?.batchId && !batchId) || (batchId && !isIsoDate(enrolledOn)) || typeof input?.dryRun !== "boolean") {
    return { ok: false, message: "That request was not valid. Reload the page and choose the file again." };
  }
  const result = await staffApi<Omit<Extract<ImportOutcome, { ok: true }>, "ok">>("/api/v1/students/import",
    { method: "POST", body: { rows, batchId, enrolledOn, dryRun: input.dryRun } }, 30000);
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  if (result.data.imported) {
    revalidatePath("/staff/students");
  revalidatePath("/staff/students/manage");
    if (batchId) revalidatePath(`/staff/batches/${batchId}`);
  }
  return { ok: true, ...result.data };
}

export type InviteCounts = { pending: number; queued: number; alreadyQueued: number; alreadyInvited: number };
export type InviteProgress = { waiting: number; sent: number; failed: number; cancelled: number };

/** Checks (dryRun) or queues invitation emails for a batch's current students, or every active student when batchId is null. */
export async function bulkInvite(input: { batchId: unknown; resend: unknown; dryRun: unknown }):
    Promise<{ ok: true; counts: InviteCounts } | { ok: false; message: string }> {
  await requireSameOriginAction();
  const batchId = input?.batchId == null ? null : (typeof input.batchId === "string" ? uuidOrNull(input.batchId) : null);
  if ((input?.batchId != null && !batchId) || typeof input?.resend !== "boolean" || typeof input?.dryRun !== "boolean") {
    return { ok: false, message: "That request was not valid. Reload the page." };
  }
  const result = await staffApi<InviteCounts>("/api/v1/student-invitations",
    { method: "POST", body: { batchId, resend: input.resend, dryRun: input.dryRun } }, 15000);
  if (!result.ok) {
    return { ok: false, message: result.status === 503 ? (result.detail ?? "Email is not set up yet.") : problemMessage(result.status, result.detail) };
  }
  return { ok: true, counts: result.data };
}

export async function inviteProgress(): Promise<InviteProgress | null> {
  await requireSameOriginAction();
  const result = await staffApi<InviteProgress>("/api/v1/student-invitations/progress");
  return result.ok ? result.data : null;
}

/** Erases a student's personal data on request (staff only; the student's number must be typed again). Audited. */
export async function eraseStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const studentId = uuidOrNull(form.get("studentId"));
  const confirmStudentNumber = String(form.get("confirm") ?? "").trim();
  const reason = String(form.get("reason") ?? "").trim();
  const values = submittedValues(form, ["reason"]);
  if (!studentId) return { ok: false, message: "That request was not valid. Reload the page." };
  if (!confirmStudentNumber) return { ok: false, message: "Type the student’s number to confirm.", values };
  if (reason.length < 3 || reason.length > 300) return { ok: false, message: "Give a reason of 3 to 300 characters.", values };
  const result = await staffApi<{ placeholderNumber: string }>(`/api/v1/students/${studentId}/erasure`, { method: "POST", body: { confirmStudentNumber, reason } });
  if (!result.ok) {
    return { ok: false, values, message: result.status === 403 ? "Only staff can erase a student’s data."
      : result.status === 400 && result.detail?.includes("confirm") ? "That isn’t this student’s number." : problemMessage(result.status, result.detail) };
  }
  revalidatePath(`/staff/students/${studentId}`);
  revalidatePath("/staff/students");
  revalidatePath("/staff/students/manage");
  return { ok: true, message: `Erased. The record is now ${result.data.placeholderNumber}.` };
}
