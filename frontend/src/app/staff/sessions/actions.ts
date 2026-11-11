"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parseSessionForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

// Every action re-validates its input; the API re-checks the caller's role, the academy
// of every batch, that added batches are active, and the record version.

const sessionFields = ["title", "date", "startTime", "endTime", "zoomWebinarId"] as const;

function valuesOf(form: FormData) {
  return { ...submittedValues(form, sessionFields), batchIds: form.getAll("batchIds").join(",") };
}

export async function scheduleSession(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = valuesOf(form);
  const input = parseSessionForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi<{ id: string; title: string }>("/api/v1/class-sessions", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/sessions");
  return { ok: true, message: `${result.data.title} scheduled.`, values: { link: `/staff/sessions/${result.data.id}` } };
}

export async function updateSession(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const version = Number(form.get("version"));
  const status = form.get("status");
  const values = valuesOf(form);
  if (!id || !Number.isSafeInteger(version) || version < 0 || (status !== "SCHEDULED" && status !== "CANCELLED")) {
    return { ok: false, message: "That request was not valid. Reload the page." };
  }
  const input = parseSessionForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi(`/api/v1/class-sessions/${id}`, { method: "PUT", body: { ...input, status, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath(`/staff/sessions/${id}`);
  revalidatePath("/staff/sessions");
  return { ok: true, message: form.get("intent") === "status"
    ? (status === "CANCELLED" ? "Class cancelled." : "Class restored.")
    : "Saved. Expected students follow the new date and batches." };
}
