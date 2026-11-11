"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { parseBatchForm, parseCourseForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

const courseFields = ["code", "title"] as const;
const batchFields = ["courseId", "code", "name", "startsOn", "endsOn"] as const;

// Every action re-validates its input; the API re-checks the caller's role and academy on each call.

export async function createCourse(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, courseFields);
  const input = parseCourseForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi("/api/v1/courses", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/courses");
  return { ok: true, message: `Course ${input.code.toUpperCase()} created.` };
}

export async function createBatch(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const values = submittedValues(form, batchFields);
  const input = parseBatchForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const result = await staffApi("/api/v1/batches", { method: "POST", body: input });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  revalidatePath("/staff/courses");
  return { ok: true, message: `Batch ${input.code.toUpperCase()} created.` };
}

/** Deactivates or reactivates a course or batch, keeping its other details. */
export async function setActive(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const kind = form.get("kind");
  const id = uuidOrNull(form.get("id"));
  const version = Number(form.get("version"));
  const active = form.get("active") === "true";
  if ((kind !== "course" && kind !== "batch") || !id || !Number.isSafeInteger(version) || version < 0) {
    return { ok: false, message: "That request was not valid. Reload the page." };
  }
  const path = kind === "course" ? `/api/v1/courses/${id}` as const : `/api/v1/batches/${id}` as const;
  const current = await staffApi<Record<string, unknown>>(path);
  if (!current.ok) return { ok: false, message: problemMessage(current.status, current.detail) };
  const record = current.data;
  const body = kind === "course"
    ? { code: record.code, title: record.title, active, version }
    : { code: record.code, name: record.name, startsOn: record.startsOn, endsOn: record.endsOn, active, version };
  const result = await staffApi(path, { method: "PUT", body });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/courses");
  return { ok: true, message: `${kind === "course" ? "Course" : "Batch"} ${active ? "reactivated" : "deactivated"}.` };
}
