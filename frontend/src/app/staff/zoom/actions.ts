"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { occurrenceIdOrNull, parseReason, problemMessage, submittedValues, uuidOrNull, zoomEmailOrNull, type FormState } from "@/lib/staff-forms";

// Owners and administrators only; the API re-checks the role and academy, and records
// every decision and replay with its reason in the audit trail.

const invalid: FormState = { ok: false, message: "That request was not valid. Reload the page." };

/** Links an occurrence to a class, or (intent "not-class") records that it was not a class. Replaces any earlier decision. */
export async function decideOccurrence(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const occurrenceUuid = occurrenceIdOrNull(form.get("occurrenceUuid"));
  const notClass = form.get("intent") === "not-class";
  const sessionId = notClass ? null : uuidOrNull(form.get("sessionId"));
  const values = submittedValues(form, ["reason", "sessionId"]);
  if (!occurrenceUuid) return invalid;
  if (!notClass && !sessionId) return { ok: false, message: "Choose the class this webinar was.", values };
  const reason = parseReason(form);
  if (typeof reason !== "string") return { ok: false, message: reason.error, values };
  const result = await staffApi("/api/v1/zoom/occurrence-links", { method: "POST", body: { occurrenceUuid, sessionId, reason } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/zoom");
  return { ok: true, message: notClass ? "Marked as not a class." : "Linked to the class." };
}

export async function autoLinkOccurrences(): Promise<FormState> {
  await requireSameOriginAction();
  const result = await staffApi<{ linked: number; stillUndecided: number }>("/api/v1/zoom/occurrences/auto-link", { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/zoom");
  const { linked, stillUndecided } = result.data;
  return { ok: true, message: `Linked ${linked} occurrence${linked === 1 ? "" : "s"}; ${stillUndecided} still need${stillUndecided === 1 ? "s" : ""} review.` };
}

/** Puts a dead-lettered receipt back in the processing queue with a fresh attempt count. */
export async function replayReceipt(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const values = submittedValues(form, ["reason"]);
  if (!id) return invalid;
  const reason = parseReason(form);
  if (typeof reason !== "string") return { ok: false, message: reason.error, values };
  const result = await staffApi(`/api/v1/zoom/receipts/${id}/replay`, { method: "POST", body: { reason } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/zoom");
  return { ok: true, message: "Queued for processing again." };
}

/**
 * Records which student a Zoom email belongs to (by student number), or (intent "not-student")
 * that it is not a student. Applies to every webinar and replaces any earlier decision.
 */
export async function decideIdentity(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const email = zoomEmailOrNull(form.get("email"));
  const notStudent = form.get("intent") === "not-student";
  const values = submittedValues(form, ["reason", "studentNumber"]);
  if (!email) return invalid;
  let studentId: string | null = null;
  if (!notStudent) {
    const number = String(form.get("studentNumber") ?? "").trim().toUpperCase();
    if (!number) return { ok: false, message: "Enter the student number this Zoom account belongs to.", values };
    if (number.length > 60) return { ok: false, message: "That is not a student number.", values };
    const found = await staffApi<{ items: { id: string; studentNumber: string }[] }>(
      `/api/v1/students?${new URLSearchParams({ q: number, limit: "20" })}`);
    if (!found.ok) return { ok: false, message: problemMessage(found.status, found.detail), values };
    const student = found.data.items.find(s => s.studentNumber === number);
    if (!student) return { ok: false, message: `No student has the number ${number}.`, values };
    studentId = student.id;
  }
  const reason = parseReason(form);
  if (typeof reason !== "string") return { ok: false, message: reason.error, values };
  const result = await staffApi("/api/v1/zoom/identity-links", { method: "POST", body: { email, studentId, reason } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/zoom");
  revalidatePath("/staff/zoom/participants");
  return { ok: true, message: notStudent ? "Marked as not a student." : "Matched to the student." };
}

/** Fetches Zoom's participant report for an ended occurrence now; it is otherwise fetched automatically. */
export async function fetchReport(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const occurrenceUuid = occurrenceIdOrNull(form.get("occurrenceUuid"));
  if (!occurrenceUuid) return invalid;
  const result = await staffApi<{ status: "FETCHED" | "FAILED"; joinSessions: number | null; error: string | null }>(
    "/api/v1/zoom/reports", { method: "POST", body: { occurrenceUuid } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/zoom");
  if (result.data.status === "FAILED") return { ok: false, message: `Zoom did not return the report (${result.data.error ?? "error"}). It will be retried automatically.` };
  return { ok: true, message: `Report fetched: ${result.data.joinSessions} join session${result.data.joinSessions === 1 ? "" : "s"}.` };
}

/** Fetches Zoom's participant report for one past run of a webinar found by link or ID. */
export async function fetchRunReport(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const webinar = String(form.get("webinar") ?? "").trim();
  const occurrenceUuid = occurrenceIdOrNull(form.get("occurrenceUuid"));
  if (!webinar || webinar.length > 500 || !occurrenceUuid) return invalid;
  const result = await staffApi<{ joinSessions: number }>("/api/v1/zoom/webinar-lookup/reports", { method: "POST", body: { webinar, occurrenceUuid } }, 30000);
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/zoom/check");
  return { ok: true, message: `Zoom's report fetched: ${result.data.joinSessions} join session${result.data.joinSessions === 1 ? "" : "s"}.` };
}

/** Links a checked run to a class; its attendance then counts there, from Zoom's report. */
export async function linkRun(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const webinar = String(form.get("webinar") ?? "").trim();
  const occurrenceUuid = occurrenceIdOrNull(form.get("occurrenceUuid"));
  const sessionId = uuidOrNull(form.get("sessionId"));
  const values = submittedValues(form, ["reason", "sessionId"]);
  if (!webinar || !occurrenceUuid) return invalid;
  if (!sessionId) return { ok: false, message: "Choose the class this run was.", values };
  const reason = parseReason(form);
  if (typeof reason !== "string") return { ok: false, message: reason.error, values };
  const result = await staffApi("/api/v1/zoom/webinar-lookup/links", { method: "POST", body: { webinar, occurrenceUuid, sessionId, reason } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/zoom/check");
  return { ok: true, message: "Linked to the class." };
}

/** Removes a decision about a Zoom email, so the automatic match by sign-in or registration email applies again. */
export async function removeIdentityDecision(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const email = zoomEmailOrNull(form.get("email"));
  const values = submittedValues(form, ["reason"]);
  if (!email) return invalid;
  const reason = parseReason(form);
  if (typeof reason !== "string") return { ok: false, message: reason.error, values };
  const result = await staffApi("/api/v1/zoom/identity-links/removals", { method: "POST", body: { email, reason } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/zoom");
  revalidatePath("/staff/zoom/participants");
  return { ok: true, message: "Decision removed; the automatic match applies again." };
}

/** Creates classes for the chosen runs of an upcoming Zoom webinar; the API re-reads the times from Zoom. */
export async function importWebinar(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const webinarId = String(form.get("webinarId") ?? "").replace(/\D/g, "");
  if (!/^\d{9,20}$/.test(webinarId)) return invalid;
  const batchIds = form.getAll("batchIds").map(v => uuidOrNull(v)).filter((v): v is string => v !== null);
  const occurrenceIds = form.getAll("occurrenceIds").map(v => String(v)).filter(v => /^[\w-]{1,100}$/.test(v));
  const title = String(form.get("title") ?? "").trim().slice(0, 200);
  if (batchIds.length === 0) return { ok: false, message: "Choose the batch or batches these classes are for." };
  if (occurrenceIds.length === 0) return { ok: false, message: "Choose at least one date." };
  const result = await staffApi<{ created: number; alreadyScheduled: number }>("/api/v1/zoom/webinar-imports",
    { method: "POST", body: { webinarId, batchIds, occurrenceIds, title: title || null } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/zoom/import");
  revalidatePath("/staff/sessions");
  const { created, alreadyScheduled } = result.data;
  return { ok: true, message: `Created ${created} class${created === 1 ? "" : "es"}`
    + (alreadyScheduled ? `; ${alreadyScheduled} already scheduled.` : ".") };
}
