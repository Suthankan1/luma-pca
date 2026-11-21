"use server";

import { revalidatePath } from "next/cache";
import { classNameOrNull } from "@/lib/academy";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { problemMessage, uuidOrNull, type FormState } from "@/lib/staff-forms";
import type { WebinarMonitor } from "@/lib/webinars";

function zoomProblem(status: number, detail: string | undefined) {
  if (status === 404) return "That webinar is not in the academy’s Zoom account (or no longer exists). Check the ID or link.";
  if (status === 503) return detail ?? "Zoom or the Academy database is not connected for this deployment.";
  if (status === 409 && detail) return detail;
  return problemMessage(status, detail);
}

/** Only a webinar ID or join link: title, time and recurrence come from Zoom. */
export async function addWebinar(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const webinar = form.get("webinar");
  const text = typeof webinar === "string" ? webinar.trim() : "";
  if (!text || text.length > 500) return { ok: false, message: "Paste the webinar’s ID or join link.", values: { webinar: text.slice(0, 300) } };
  const result = await staffApi<WebinarMonitor>("/api/v1/zoom/monitors", { method: "POST", body: { webinar: text } }, 20000);
  if (!result.ok) return { ok: false, message: zoomProblem(result.status, result.detail), values: { webinar: text.slice(0, 300) } };
  revalidatePath("/staff/sessions");
  revalidatePath("/staff");
  return { ok: true, message: `Tracking “${result.data.topic ?? result.data.webinarId}”. Its Zoom classes now show here, with attendance from each run.` };
}

/** Refresh from Zoom, stop or resume tracking, or set the Academy class: one action per tracked webinar. */
export async function webinarAction(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const id = uuidOrNull(form.get("monitorId"));
  const intent = form.get("intent");
  if (!id) return { ok: false, message: "That request was not valid. Reload the page." };
  let result;
  let done: string;
  if (intent === "sync") {
    result = await staffApi<WebinarMonitor>(`/api/v1/zoom/monitors/${id}/sync`, { method: "POST", body: {} }, 20000);
    done = result.ok && result.data.syncStatus !== "OK" ? (result.data.syncError ?? "Zoom could not be read.") : "Read again from Zoom.";
  } else if (intent === "stop" || intent === "resume") {
    result = await staffApi<WebinarMonitor>(`/api/v1/zoom/monitors/${id}/${intent}`, { method: "POST", body: {} });
    done = intent === "stop" ? "Tracking stopped. The Zoom webinar is unchanged; past classes are kept." : "Tracking again.";
  } else if (intent === "class") {
    const name = form.get("academyClassName");
    const className = typeof name === "string" && name.trim() ? name.trim().slice(0, 200) : null;
    result = await staffApi<WebinarMonitor>(`/api/v1/zoom/monitors/${id}/academy-class`, { method: "PUT", body: { academyClassName: className } });
    done = className ? `Academy class set to “${className}”.` : "Academy class cleared: runs expect no students until it is set.";
  } else {
    return { ok: false, message: "That request was not valid. Reload the page." };
  }
  if (!result.ok) return { ok: false, message: zoomProblem(result.status, result.detail) };
  revalidatePath("/staff/sessions");
  revalidatePath("/staff");
  return { ok: true, message: done };
}

export async function assignPaymentRoster(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  let id = uuidOrNull(form.get("monitorId"));
  const name = classNameOrNull(form.get("academyClassName"));
  if (!name) return { ok: false, message: "Select an Academy payment class." };
  if (!id) {
    const input = form.get("webinar");
    const webinar = typeof input === "string" ? input.trim() : "";
    if (!webinar || webinar.length > 500) return { ok: false, message: "Enter the class’s Zoom link or webinar ID." };
    const tracked = await staffApi<WebinarMonitor>("/api/v1/zoom/monitors", { method: "POST", body: { webinar } }, 20000);
    if (!tracked.ok) return { ok: false, message: zoomProblem(tracked.status, tracked.detail) };
    id = tracked.data.id;
    revalidatePath("/staff/class-types");
    revalidatePath("/staff/sessions");
  }
  const result = await staffApi<{ academyStudents: number }>(`/api/v1/zoom/monitors/${id}/payment-roster`,
    { method: "PUT", body: { academyClassName: name } }, 25000);
  if (!result.ok) return { ok: false, message: result.detail ?? problemMessage(result.status, undefined) };
  revalidatePath("/staff/class-types");
  revalidatePath("/staff/sessions");
  revalidatePath("/staff/academy");
  const count = result.data.academyStudents;
  return { ok: true, message: `${name} synced. ${count} expected ${count === 1 ? "student" : "students"} per run.` };
}
