"use server";

import { revalidatePath } from "next/cache";
import { config } from "@/lib/auth-server";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import {
  isIsoDate, parseEnrollForm, parseStudentForm, problemMessage, submittedValues, uuidOrNull, type FormState,
} from "@/lib/staff-forms";

// Every action re-validates its input; the API re-checks the caller's role and academy on each call.

const studentFields = ["studentNumber", "fullName", "email"] as const;

export async function createStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, studentFields);
  const input = parseStudentForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi<{ id: string; studentNumber: string }>("/api/v1/students", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/students");
  return { ok: true, message: `Student ${result.data.studentNumber} added. Open the record to send an invitation.`,
    values: { link: `/staff/students/${result.data.id}` } };
}

export async function updateStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const version = Number(form.get("version"));
  const fullName = String(form.get("fullName") ?? "").trim();
  const active = form.get("active") === "true";
  if (!id || !Number.isSafeInteger(version) || version < 0) return { ok: false, message: "That request was not valid. Reload the page." };
  if (!fullName || fullName.length > 200) {
    return { ok: false, message: "Enter the student’s full name, up to 200 characters.", values: { fullName: fullName.slice(0, 300) } };
  }
  const result = await staffApi(`/api/v1/students/${id}`, { method: "PUT", body: { fullName, active, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values: { fullName } };
  revalidatePath(`/staff/students/${id}`);
  revalidatePath("/staff/students");
  return { ok: true, message: "Saved." };
}

/**
 * Issues an activation invitation and returns its link once, to the administrator
 * only. The web app does not store it; issuing again cancels the previous link.
 */
export async function inviteStudent(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const accountId = uuidOrNull(form.get("accountId"));
  const studentId = uuidOrNull(form.get("studentId"));
  if (!accountId || !studentId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi<{ activationPath: string; expiresAt: string }>(`/api/v1/accounts/${accountId}/invitations`,
    { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  if (!/^\/activate#token=[A-Za-z0-9_-]{20,128}$/.test(result.data.activationPath)) {
    return { ok: false, message: problemMessage(502, undefined) };
  }
  return { ok: true, message: "Invitation created.",
    values: { link: `${config().origin}${result.data.activationPath}`, expiresAt: result.data.expiresAt } };
}

export async function enroll(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const studentId = uuidOrNull(form.get("studentId"));
  const values = submittedValues(form, ["batchId", "enrolledOn"]);
  const input = parseEnrollForm(form);
  if (!studentId) return { ok: false, message: "That request was not valid. Reload the page." };
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi(`/api/v1/batches/${input.batchId}/enrollments`,
    { method: "POST", body: { studentId, enrolledOn: input.enrolledOn } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath(`/staff/students/${studentId}`);
  return { ok: true, message: "Enrolled." };
}

/** Withdraws on a date, or re-enrols when no withdrawal date is sent. */
export async function updateEnrollment(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("id"));
  const studentId = uuidOrNull(form.get("studentId"));
  const version = Number(form.get("version"));
  const enrolledOn = form.get("enrolledOn");
  const withdrawnOn = form.get("withdrawnOn") || null;
  if (!id || !studentId || !Number.isSafeInteger(version) || version < 0 || !isIsoDate(enrolledOn)
      || (withdrawnOn !== null && !isIsoDate(withdrawnOn))) {
    return { ok: false, message: "Enter a valid withdrawal date." };
  }
  if (withdrawnOn && withdrawnOn < enrolledOn) return { ok: false, message: "A withdrawal date cannot be before the enrolment date." };
  const result = await staffApi(`/api/v1/enrollments/${id}`, { method: "PUT", body: { enrolledOn, withdrawnOn, version } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath(`/staff/students/${studentId}`);
  return { ok: true, message: withdrawnOn ? "Withdrawn." : "Re-enrolled." };
}
