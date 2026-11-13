"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parsePolicyForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

/** Records the academy's attendance rule. Owners only; the API re-checks and audits every change. */
export async function savePolicy(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, ["minConnectedPercent", "lateAfterMinutes", "description"]);
  const parsed = parsePolicyForm(form);
  if ("error" in parsed) return { ok: false, message: parsed.error, values };
  const versionText = form.get("version");
  const version = typeof versionText === "string" && /^\d+$/.test(versionText) ? Number(versionText) : null;
  const result = await staffApi("/api/v1/attendance-policy", { method: "PUT", body: { ...parsed, version } });
  if (!result.ok) {
    return { ok: false, values, message: result.status === 403 ? "Only an academy owner can record the attendance rule."
      : result.status === 409 ? "Someone else changed the rule. Reload the page and try again." : problemMessage(result.status, result.detail) };
  }
  revalidatePath("/staff/attendance");
  return { ok: true, message: "Attendance rule saved." };
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
