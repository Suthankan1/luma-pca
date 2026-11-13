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
  revalidatePath("/student");
  return { ok: true, message: "Appeal sent. The academy will review it." };
}
