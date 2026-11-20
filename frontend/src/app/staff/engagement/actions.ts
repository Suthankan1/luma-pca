"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parsePulseForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

/** Switches engagement on or off with the consent wording. Staff only; audited. */
export async function saveEngagementSettings(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, ["consentText"]);
  const consentText = String(form.get("consentText") ?? "").trim();
  const enabled = form.get("enabled") === "on";
  const versionText = form.get("version");
  const version = typeof versionText === "string" && /^\d+$/.test(versionText) ? Number(versionText) : null;
  if (consentText.length < 20 || consentText.length > 2000) return { ok: false, message: "Write the consent wording in 20 to 2000 characters.", values };
  const result = await staffApi("/api/v1/engagement-settings", { method: "PUT", body: { enabled, consentText, version } });
  if (!result.ok) {
    return { ok: false, values, message: result.status === 403 ? "Only staff can switch engagement on or off."
      : result.status === 409 ? "Someone else changed these settings. Reload the page and try again." : problemMessage(result.status, result.detail) };
  }
  revalidatePath("/staff/attendance");
  return { ok: true, message: enabled ? "Engagement is on. Students are asked for consent in their portal." : "Engagement is off." };
}

export async function openPulse(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const sessionId = uuidOrNull(form.get("sessionId"));
  const values = submittedValues(form, ["prompt", "options", "minutes"]);
  if (!sessionId) return { ok: false, message: "That request was not valid. Reload the page." };
  const parsed = parsePulseForm(form);
  if ("error" in parsed) return { ok: false, message: parsed.error, values };
  const result = await staffApi(`/api/v1/class-sessions/${sessionId}/pulses`, { method: "POST", body: parsed });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath(`/staff/sessions/${sessionId}`);
  return { ok: true, message: "Question opened." };
}

export async function closePulse(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const sessionId = uuidOrNull(form.get("sessionId"));
  const questionId = uuidOrNull(form.get("questionId"));
  if (!sessionId || !questionId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi(`/api/v1/class-sessions/${sessionId}/pulses/${questionId}/closure`, { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/sessions/${sessionId}`);
  return { ok: true, message: "Question closed." };
}
