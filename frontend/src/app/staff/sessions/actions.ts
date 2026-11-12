"use server";

import { revalidatePath } from "next/cache";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { formatClassTime, formatDate, isRealDate, isTimeZone, parseSeriesForm, parseSessionForm, problemMessage, submittedValues, uuidOrNull, type FormState } from "@/lib/staff-forms";

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
    : "Saved. Expected students and automatic Zoom links follow the new date, time and webinar." };
}

type SeriesResult = { created: boolean; seriesId: string | null;
  occurrences: { localDate: string; startsAt: string; endsAt: string; id: string | null }[];
  conflicts: { localDate: string; sessionId: string; title: string; startsAt: string; endsAt: string }[] };

export type SeriesState = null | {
  ok: boolean; message: string; values?: Record<string, string>;
  /** Present after a clean preview: what Create will make, and the inputs it was made from. */
  preview?: { classes: string[]; key: string };
  conflicts?: { label: string; link: string }[];
};

const seriesFields = ["title", "firstDate", "lastDate", "startTime", "endTime", "zoomWebinarId", "skipDates"] as const;

/** Previews (intent "preview") or creates a weekly series; creating needs a clean preview of the same details. */
export async function scheduleSeries(_previous: SeriesState, form: FormData): Promise<SeriesState> {
  await requireSameOriginAction();
  const values = { ...submittedValues(form, seriesFields), batchIds: form.getAll("batchIds").join(","), weekdays: form.getAll("weekdays").join(",") };
  const input = parseSeriesForm(form);
  if ("error" in input) return { ok: false, message: input.error, values };
  const key = JSON.stringify(input);
  const create = form.get("intent") === "create";
  if (create && form.get("previewKey") !== key) {
    return { ok: false, message: "The details changed since the preview. Preview the classes again.", values };
  }
  const result = await staffApi<SeriesResult>("/api/v1/class-sessions/series", { method: "POST", body: { ...input, dryRun: !create } }, 15000);
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  const { occurrences, conflicts } = result.data;
  if (conflicts.length > 0) {
    return { ok: false, values,
      message: `${conflicts.length === 1 ? "One class overlaps" : `${conflicts.length} classes overlap`} a class already scheduled for these batches. Add those dates to “Dates to skip”, or change the existing class.`,
      conflicts: conflicts.map(c => ({ label: `${formatDate(c.localDate)} overlaps “${c.title}”, ${formatClassTime(c.startsAt, c.endsAt, input.timeZone)}`,
        link: `/staff/sessions/${c.sessionId}` })) };
  }
  const span = `${formatDate(occurrences[0].localDate)} – ${formatDate(occurrences[occurrences.length - 1].localDate)}`;
  if (!result.data.created) {
    return { ok: true, values, message: `${occurrences.length} class${occurrences.length === 1 ? "" : "es"}, ${span}. Nothing has been created yet.`,
      preview: { key, classes: occurrences.map(o => formatClassTime(o.startsAt, o.endsAt, input.timeZone)) } };
  }
  revalidatePath("/staff/sessions");
  return { ok: true, message: `Created ${occurrences.length} class${occurrences.length === 1 ? "" : "es"}, ${span}.`,
    values: { link: `/staff/sessions?from=${occurrences[0].localDate}` } };
}

/** Cancels the series' scheduled classes from a date onwards. */
export async function cancelSeries(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const seriesId = uuidOrNull(form.get("seriesId"));
  const sessionId = uuidOrNull(form.get("sessionId"));
  const fromDate = form.get("fromDate");
  if (!seriesId || !sessionId) return { ok: false, message: "That request was not valid. Reload the page." };
  if (!isRealDate(fromDate)) return { ok: false, message: "Enter the first date to cancel." };
  const result = await staffApi<{ cancelled: number }>(`/api/v1/class-sessions/series/${seriesId}/cancellations`,
    { method: "POST", body: { fromDate } });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  revalidatePath("/staff/sessions");
  revalidatePath(`/staff/sessions/${sessionId}`);
  const n = result.data.cancelled;
  return { ok: true, message: n === 0 ? "No scheduled classes in this series from that date." : `${n} class${n === 1 ? "" : "es"} cancelled from ${formatDate(fromDate)}.` };
}

type SeriesEditResult = { applied: boolean; occurrences: SeriesResult["occurrences"]; conflicts: SeriesResult["conflicts"] };
export type SeriesEditState = SeriesState;

/** Previews (intent "preview") or applies changes to the rest of a series; applying needs a clean preview of the same details. */
export async function editSeries(_previous: SeriesEditState, form: FormData): Promise<SeriesEditState> {
  await requireSameOriginAction();
  const fields = ["fromDate", "title", "startTime", "endTime", "zoomWebinarId"] as const;
  const values: Record<string, string> = { ...submittedValues(form, fields), clearWebinar: form.get("clearWebinar") === "yes" ? "yes" : "" };
  const seriesId = uuidOrNull(form.get("seriesId"));
  const sessionId = uuidOrNull(form.get("sessionId"));
  const zone = String(form.get("timeZone") ?? "");
  if (!seriesId || !sessionId || !isTimeZone(zone)) return { ok: false, message: "That request was not valid. Reload the page.", values };
  const fromDate = values.fromDate;
  const title = (values.title ?? "").trim();
  const startTime = values.startTime ?? "";
  const endTime = values.endTime ?? "";
  const webinar = (values.zoomWebinarId ?? "").replace(/[\s-]/g, "");
  const clearWebinar = values.clearWebinar === "yes";
  if (!isRealDate(fromDate)) return { ok: false, message: "Enter the first date to change.", values };
  if (title.length > 200) return { ok: false, message: "A title can be at most 200 characters.", values };
  if (!startTime !== !endTime) return { ok: false, message: "Give both a start and an end time, or neither.", values };
  if (startTime && endTime <= startTime) return { ok: false, message: "The class must end after it starts, on the same day.", values };
  if (webinar && !/^\d{9,20}$/.test(webinar)) return { ok: false, message: "Enter the Zoom webinar ID as digits, such as 812 3456 7890.", values };
  if (webinar && clearWebinar) return { ok: false, message: "Enter a new webinar ID or remove it, not both.", values };
  if (!title && !startTime && !webinar && !clearWebinar) return { ok: false, message: "Change the title, the time or the webinar.", values };
  const body = { fromDate, title: title || null, startTime: startTime || null, endTime: endTime || null, zoomWebinarId: webinar || null, clearWebinar };
  const key = JSON.stringify(body);
  const apply = form.get("intent") === "apply";
  if (apply && form.get("previewKey") !== key) return { ok: false, message: "The details changed since the preview. Preview again.", values };
  const result = await staffApi<SeriesEditResult>(`/api/v1/class-sessions/series/${seriesId}/changes`,
    { method: "POST", body: { ...body, dryRun: !apply } }, 15000);
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail), values };
  const { occurrences, conflicts } = result.data;
  if (conflicts.length > 0) {
    return { ok: false, values,
      message: `${conflicts.length === 1 ? "One class would overlap" : `${conflicts.length} classes would overlap`} a class already scheduled for these batches. Nothing has changed.`,
      conflicts: conflicts.map(c => ({ label: `${formatDate(c.localDate)} overlaps “${c.title}”, ${formatClassTime(c.startsAt, c.endsAt, zone)}`,
        link: `/staff/sessions/${c.sessionId}` })) };
  }
  const n = occurrences.length;
  if (!result.data.applied) {
    return { ok: true, values, message: `${n} class${n === 1 ? "" : "es"} will change. Nothing has been changed yet.`,
      preview: { key, classes: occurrences.map(o => formatClassTime(o.startsAt, o.endsAt, zone)) } };
  }
  revalidatePath("/staff/sessions");
  revalidatePath(`/staff/sessions/${sessionId}`);
  return { ok: true, message: `Changed ${n} class${n === 1 ? "" : "es"} from ${formatDate(fromDate)}. Zoom links follow the new time and webinar.` };
}
