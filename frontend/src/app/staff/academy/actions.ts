"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { ACADEMY_COURSE, batchCodeFor, classNameOrNull, syncSummary, type AcademyActionState, type SyncResult } from "@/lib/academy";
import { problemMessage, uuidOrNull } from "@/lib/staff-forms";

type Course = { id: string; code: string; active: boolean };
type Batch = { id: string; courseId: string; code: string };

function academyProblem(status: number, detail: string | undefined) {
  if (status === 503) return detail ?? "The Academy database could not be reached. Please try again shortly.";
  if (status === 404) return "That Academy class or batch no longer exists. Reload the page.";
  return problemMessage(status, detail);
}

async function sync(className: string): Promise<AcademyActionState> {
  const result = await staffApi<SyncResult>("/api/v1/academy/classes/sync", { method: "POST", body: { className } }, 60000);
  revalidatePath("/staff/academy");
  if (!result.ok) return { ok: false, message: academyProblem(result.status, result.detail) };
  return { ok: true, message: syncSummary(result.data), sync: result.data };
}

/**
 * One step for a class with no batch yet: a batch named after the class (in the "Academy classes" course, created the
 * first time), linked to the class, then synced. Staff never type course or batch details for Academy classes.
 */
async function setUpClass(form: FormData): Promise<AcademyActionState> {
  const className = classNameOrNull(form.get("className"));
  if (!className) return { ok: false, message: "That request was not valid. Reload the page." };

  const [courses, batches] = await Promise.all([staffApi<Course[]>("/api/v1/courses"), staffApi<Batch[]>("/api/v1/batches")]);
  if (!courses.ok) return { ok: false, message: academyProblem(courses.status, courses.detail) };
  if (!batches.ok) return { ok: false, message: academyProblem(batches.status, batches.detail) };
  let course = courses.data.find(c => c.code === ACADEMY_COURSE.code);
  if (course && !course.active) {
    return { ok: false, message: `The ${ACADEMY_COURSE.code} course is inactive. Reactivate it under Courses & batches first.` };
  }
  if (!course) {
    const made = await staffApi<Course>("/api/v1/courses", { method: "POST", body: ACADEMY_COURSE });
    if (!made.ok) return { ok: false, message: academyProblem(made.status, made.detail) };
    course = made.data;
  }
  const taken = batches.data.filter(b => b.courseId === course.id).map(b => b.code);
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date());
  const batch = await staffApi<Batch>("/api/v1/batches", { method: "POST",
    body: { courseId: course.id, code: batchCodeFor(className, taken), name: className.slice(0, 200), startsOn: today, endsOn: null } });
  if (!batch.ok) return { ok: false, message: academyProblem(batch.status, batch.detail) };
  const link = await staffApi("/api/v1/academy/classes/link", { method: "PUT", body: { className, batchId: batch.data.id } });
  if (!link.ok) return { ok: false, message: academyProblem(link.status, link.detail) };
  return sync(className);
}

/** Links a class to a batch that already exists (for example one with classes and attendance history). */
async function linkClass(form: FormData): Promise<AcademyActionState> {
  const className = classNameOrNull(form.get("className"));
  const batchId = uuidOrNull(form.get("batchId"));
  if (!className) return { ok: false, message: "That request was not valid. Reload the page." };
  if (!batchId) return { ok: false, message: "Choose the batch for this class." };
  const link = await staffApi("/api/v1/academy/classes/link", { method: "PUT", body: { className, batchId } });
  if (!link.ok) return { ok: false, message: academyProblem(link.status, link.detail) };
  return sync(className);
}

/**
 * Every action on one Academy class goes through here, so the row keeps showing the outcome when the page refreshes
 * and the row switches from "Set up" to "Sync students".
 */
export async function academyClassAction(_previous: AcademyActionState, form: FormData): Promise<AcademyActionState> {
  await requireSameOriginAction();
  const intent = form.get("intent");
  if (intent === "setup") return setUpClass(form);
  if (intent === "link") return linkClass(form);
  const className = classNameOrNull(form.get("className"));
  if (intent !== "sync" || !className) return { ok: false, message: "That request was not valid. Reload the page." };
  return sync(className);
}
