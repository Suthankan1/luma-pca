"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { occurrenceIdOrNull, parseReason, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

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
