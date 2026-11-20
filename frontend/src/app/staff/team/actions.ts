"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parseStaffForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

// Every action re-validates its input; the API re-checks the staff role, academy, self-disable and
// last-active-staff rules on each call and records each change in the audit trail.

export async function createStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, ["email"]);
  const input = parseStaffForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi<{ id: string; email: string }>("/api/v1/staff-accounts", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/team");
  return { ok: true, message: `${result.data.email} added. Open their record to send an invitation.`,
    values: { link: `/staff/team/${result.data.id}` } };
}

export async function disableStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  if (!id) return { ok: false, message: "That request was not valid. Reload the page." };
  if (form.get("confirm") !== "yes") return { ok: false, message: "Tick the box to confirm." };
  const result = await staffApi(`/api/v1/staff-accounts/${id}/disable`, { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/team/${id}`);
  revalidatePath("/staff/team");
  return { ok: true, message: "Account disabled. The person has been signed out everywhere." };
}

export async function enableStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  if (!id) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi<{ status: string }>(`/api/v1/staff-accounts/${id}/enable`,
    { method: "POST", body: { requireNewPassword: form.get("requireNewPassword") === "yes" } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/team/${id}`);
  revalidatePath("/staff/team");
  return { ok: true, message: result.data.status === "ACTIVE" ? "Account re-enabled. They can sign in again."
    : "Account re-enabled and waiting for activation. Send them an invitation to choose a password." };
}
