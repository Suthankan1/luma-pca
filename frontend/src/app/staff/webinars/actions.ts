"use server";

import { revalidatePath } from "next/cache";
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
  revalidatePath("/staff/webinars");
  return { ok: true, message: `Monitoring “${result.data.topic ?? result.data.webinarId}”. Every run Zoom starts is now recorded.` };
}

/** Refresh from Zoom, stop or resume monitoring, or set the Academy class: one action per webinar card. */
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
    done = intent === "stop" ? "Monitoring stopped. The Zoom webinar is unchanged; past runs are kept." : "Monitoring resumed.";
  } else if (intent === "class") {
    const name = form.get("academyClassName");
    const className = typeof name === "string" && name.trim() ? name.trim().slice(0, 200) : null;
    result = await staffApi<WebinarMonitor>(`/api/v1/zoom/monitors/${id}/academy-class`, { method: "PUT", body: { academyClassName: className } });
    done = className ? `Academy class set to “${className}”.` : "Academy class cleared: runs expect no students until it is set.";
  } else {
    return { ok: false, message: "That request was not valid. Reload the page." };
  }
  if (!result.ok) return { ok: false, message: zoomProblem(result.status, result.detail) };
  revalidatePath("/staff/webinars");
  return { ok: true, message: done };
}
