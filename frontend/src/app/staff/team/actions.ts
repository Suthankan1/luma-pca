"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parseAssignForm, parseRoles, parseStaffForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

// Every action re-validates its input; the API re-checks role, academy, self-change and
// last-owner rules on each call and records each change in the audit trail.

export async function createStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = { ...submittedValues(form, ["email"]), roles: form.getAll("roles").join(",") };
  const input = parseStaffForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi<{ id: string; email: string }>("/api/v1/staff-accounts", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/team");
  return { ok: true, message: `${result.data.email} added. Open their record to send an invitation.`,
    values: { link: `/staff/team/${result.data.id}` } };
}

export async function changeRoles(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const version = Number(form.get("version"));
  const roles = parseRoles(form);
  if (!id || !Number.isSafeInteger(version) || version < 0) return { ok: false, message: "That request was not valid. Reload the page." };
  if ("error" in roles) return { ok: false, message: roles.error };
  const result = await staffApi(`/api/v1/staff-accounts/${id}/roles`, { method: "PUT", body: { roles, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/team/${id}`);
  revalidatePath("/staff/team");
  return { ok: true, message: "Roles saved. They apply from the person’s next request." };
}

export async function disableStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  if (!id) return { ok: false, message: "That request was not valid. Reload the page." };
  if (form.get("confirm") !== "yes") return { ok: false, message: "Tick the box to confirm. Disabling cannot be undone yet." };
  const result = await staffApi(`/api/v1/staff-accounts/${id}/disable`, { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/team/${id}`);
  revalidatePath("/staff/team");
  return { ok: true, message: "Account disabled. The person has been signed out everywhere." };
}

export async function assignStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const batchId = uuidOrNull(form.get("batchId"));
  const values = submittedValues(form, ["email", "role"]);
  const input = parseAssignForm(form);
  if (!batchId) return { ok: false, message: "That request was not valid. Reload the page." };
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi(`/api/v1/batches/${batchId}/assignments`, { method: "POST", body: input });
  if (!result.ok) {
    // The API reports unknown and other-academy emails identically as not found.
    const message = result.status === 404 ? "No staff account with that email in this academy." : problemMessage(result.status, result.detail);
    return { ok: false, message, values };
  }
  revalidatePath(`/staff/batches/${batchId}`);
  return { ok: true, message: "Assigned. They can open this roster from their next request." };
}

export async function unassignStaff(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const batchId = uuidOrNull(form.get("batchId"));
  if (!id || !batchId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi(`/api/v1/assignments/${id}`, { method: "DELETE" });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/batches/${batchId}`);
  return { ok: true, message: "Removed." };
}
