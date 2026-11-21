"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { outcomeOrNull, parsePolicyForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

/** Records the academy's attendance rule. Staff only; the API re-checks and audits every change. */
export async function savePolicy(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, ["minConnectedPercent", "lateAfterMinutes", "description"]);
  const parsed = parsePolicyForm(form);
  if ("error" in parsed) return { ok: false, message: parsed.error, values };
  const versionText = form.get("version");
  const version = typeof versionText === "string" && /^\d+$/.test(versionText) ? Number(versionText) : null;
  const autoFinalise = form.get("autoFinalise") === "on";
  const result = await staffApi("/api/v1/attendance-policy", { method: "PUT", body: { ...parsed, version, autoFinalise } });
  if (!result.ok) {
    return { ok: false, values, message: result.status === 403 ? "Only staff can record the attendance rule."
      : result.status === 409 ? "Someone else changed the rule. Reload the page and try again." : problemMessage(result.status, result.detail) };
  }
  revalidatePath("/staff/attendance");
  return { ok: true, message: "Attendance rule saved." };
}

/** Finalises every class whose Zoom reports are all in; the others stay listed with what they wait for. */
export async function finaliseAllReady(): Promise<FormState> {
  await requireSameOriginAction();
  const result = await staffApi<{ finalised: number; notReady: number; problems: string[] }>("/api/v1/attendance/finalisations",
    { method: "POST", body: {} }, 30000);
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/attendance");
  const { finalised, problems } = result.data;
  return { ok: problems.length === 0, message: `Finalised ${finalised} class${finalised === 1 ? "" : "es"}.`
    + (problems.length ? ` Not finalised: ${problems.join("; ")}` : "") };
}

/** Finalises a class's attendance against the academy's rule, from Zoom's report. */
export async function finaliseAttendance(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const sessionId = uuidOrNull(form.get("sessionId"));
  if (!sessionId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi<{ present: number; late: number; absent: number }>(`/api/v1/class-sessions/${sessionId}/attendance/finalisation`,
    { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/sessions/${sessionId}`);
  const { present, late, absent } = result.data;
  return { ok: true, message: `Attendance is final: ${present} present, ${late} late, ${absent} absent.` };
}

/** Accepts (correcting the record) or rejects a student's appeal, with a note the student sees. */
export async function resolveAppeal(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const appealId = uuidOrNull(form.get("appealId"));
  const decision = form.get("decision") === "ACCEPT" ? "ACCEPT" : "REJECT";
  const outcome = outcomeOrNull(form.get("outcome"));
  const note = String(form.get("note") ?? "").trim();
  const values = submittedValues(form, ["note", "outcome"]);
  if (!appealId) return { ok: false, message: "That request was not valid. Reload the page." };
  if (decision === "ACCEPT" && !outcome) return { ok: false, message: "Choose the outcome to record when accepting.", values };
  if (note.length < 3 || note.length > 500) return { ok: false, message: "Write a note of 3 to 500 characters; the student sees it.", values };
  const result = await staffApi(`/api/v1/attendance-appeals/${appealId}/resolution`, { method: "POST", body: { decision, outcome, note } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/attendance");
  return { ok: true, message: decision === "ACCEPT" ? "Appeal accepted; the record is corrected." : "Appeal rejected." };
}
