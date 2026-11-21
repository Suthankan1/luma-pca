"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, studentApi } from "@/lib/staff-api";
import { problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

/** A student appeals one of their own final attendance records; staff decide. */
export async function appealRecord(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const recordId = uuidOrNull(form.get("recordId"));
  const message = String(form.get("message") ?? "").trim();
  const values = submittedValues(form, ["message"]);
  if (!recordId) return { ok: false, message: "That request was not valid. Reload the page." };
  if (message.length < 10 || message.length > 1000) return { ok: false, message: "Explain your appeal in 10 to 1000 characters.", values };
  const result = await studentApi(`/api/v1/me/attendance/${recordId}/appeals`, { method: "POST", body: { message } });
  if (!result.ok) {
    return { ok: false, values, message: result.status === 409 ? "An appeal for this class is already waiting for a decision."
      : problemMessage(result.status, result.detail) };
  }
  revalidatePath("/student/attendance");
  revalidatePath("/student");
  return { ok: true, message: "Appeal sent. The academy will review it." };
}

/** The student's own consent to engagement, and separately to appearing by name in staff rankings. */
export async function saveConsent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const granted = form.get("granted") === "yes";
  const leaderboardOptIn = granted && form.get("leaderboard") === "on";
  const consentVersion = Number(form.get("consentVersion"));
  if (!Number.isInteger(consentVersion)) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await studentApi("/api/v1/me/engagement-consent", { method: "PUT", body: { granted, leaderboardOptIn, consentVersion } });
  if (!result.ok) return { ok: false, message: result.status === 409 ? "The wording has changed. Reload the page and read it again." : problemMessage(result.status, result.detail) };
  revalidatePath("/student");
  revalidatePath("/student/attendance");
  revalidatePath("/student/live");
  return { ok: true, message: granted ? "Thank you. You can react and answer during classes." : "Engagement is off for you. Nothing is recorded." };
}

export async function sendReaction(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const sessionId = uuidOrNull(form.get("sessionId"));
  const kind = String(form.get("kind") ?? "");
  if (!sessionId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await studentApi(`/api/v1/me/class-sessions/${sessionId}/reactions`, { method: "POST", body: { kind } });
  if (!result.ok) return { ok: false, message: result.status === 409 ? (result.detail ?? "Not now.") : problemMessage(result.status, result.detail) };
  return { ok: true, message: "Sent." };
}

export async function answerPulse(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const sessionId = uuidOrNull(form.get("sessionId"));
  const questionId = uuidOrNull(form.get("questionId"));
  const option = Number(form.get("option"));
  if (!sessionId || !questionId || !Number.isInteger(option)) return { ok: false, message: "Choose an answer." };
  const result = await studentApi(`/api/v1/me/class-sessions/${sessionId}/pulses/${questionId}/answers`, { method: "POST", body: { option } });
  if (!result.ok) return { ok: false, message: result.status === 409 ? (result.detail ?? "Not now.") : problemMessage(result.status, result.detail) };
  revalidatePath(`/student/classes/${sessionId}`);
  return { ok: true, message: "Answer sent." };
}
