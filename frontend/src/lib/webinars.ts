/** Types and pure helpers for monitored Zoom webinars (Zoom owns schedules; LUMA records actual runs). Unit-tested. */

export type ZoomOccurrence = { occurrenceId: string | null; startTime: string | null; durationMinutes: number | null; status: string | null };

export type WebinarRun = {
  occurrenceUuid: string; classSessionId: string; state: "LIVE" | "ENDED"; firstSeenAt: string; actualStartedAt: string | null;
  actualEndedAt: string | null; reported: boolean; connectedNow: number; people: number;
};

export type WebinarMonitor = {
  id: string; webinarId: string; topic: string | null; timeZone: string | null; scheduledStartAt: string | null;
  durationMinutes: number | null; recurring: boolean; occurrences: ZoomOccurrence[]; syncStatus: "OK" | "FAILED" | "NOT_FOUND";
  syncedAt: string | null; syncError: string | null; academyClassName: string | null; classSource: string | null;
  classResolved: boolean; classBatchId: string | null; active: boolean; addedAt: string; live: WebinarRun | null;
  runs: WebinarRun[]; version: number;
};

export type ClassRun = {
  webinarId: string; topic: string | null; occurrenceUuid: string; state: "LIVE" | "ENDED"; firstSeenAt: string;
  actualStartedAt: string | null; actualEndedAt: string | null; scheduledStartAt: string | null; scheduledDurationMinutes: number | null;
  timeZone: string | null; reported: boolean; connectedNow: number; people: number; academyClassName: string | null; classResolved: boolean;
};

export const ACADEMY_ZONE = "Asia/Colombo";

/** "844 0000 0101" — Zoom's own grouping, for reading aloud. */
export function formatWebinarId(id: string) {
  return id.length === 11 ? `${id.slice(0, 3)} ${id.slice(3, 7)} ${id.slice(7)}` : id.replace(/(\d{3})(?=\d)/g, "$1 ");
}

/** "Wed 18 Nov 2026, 18:00" in the given zone (the academy's when Zoom gives none); the same on every ICU version. */
export function formatWhen(instant: string, timeZone: string | null) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: timeZone ?? ACADEMY_ZONE, weekday: "short", day: "numeric",
    month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false }).formatToParts(new Date(instant))
    .map(part => [part.type, part.value]));
  return `${parts.weekday} ${parts.day} ${parts.month} ${parts.year}, ${parts.hour}:${parts.minute}`;
}

export function formatTime(instant: string, timeZone: string | null) {
  return new Intl.DateTimeFormat("en-GB", { timeZone: timeZone ?? ACADEMY_ZONE, hour: "2-digit", minute: "2-digit", hour12: false })
    .format(new Date(instant));
}

/** "1 h 05 min" from seconds; "0 min" for nothing. */
export function formatElapsed(seconds: number) {
  const minutes = Math.max(0, Math.floor(seconds / 60));
  const hours = Math.floor(minutes / 60);
  return hours ? `${hours} h ${String(minutes % 60).padStart(2, "0")} min` : `${minutes} min`;
}

/** The run's start: Zoom's actual start, else (start event missed) its first event, said so. */
export function runStart(run: { actualStartedAt: string | null; firstSeenAt: string }) {
  return run.actualStartedAt ? { at: run.actualStartedAt, known: true } : { at: run.firstSeenAt, known: false };
}

/** Zoom's schedule in words; never invented when Zoom has none. */
export function zoomSchedule(m: Pick<WebinarMonitor, "scheduledStartAt" | "durationMinutes" | "timeZone" | "recurring">) {
  if (!m.scheduledStartAt) return m.recurring ? "Recurring, no fixed time in Zoom" : "No Zoom schedule";
  const length = m.durationMinutes ? ` · ${m.durationMinutes} min` : "";
  return `${formatWhen(m.scheduledStartAt, m.timeZone)}${length} (${m.timeZone ?? ACADEMY_ZONE})`;
}

/** Zoom occurrences still to come, soonest first (deleted ones left out). */
export function upcomingOccurrences(occurrences: ZoomOccurrence[], now: number, limit = 5) {
  return occurrences.filter(o => o.startTime && Date.parse(o.startTime) >= now - 3 * 3600_000 && o.status !== "deleted")
    .sort((a, b) => Date.parse(a.startTime!) - Date.parse(b.startTime!)).slice(0, limit);
}

/** Has the Zoom copy gone stale? (last sync failed, or older than a day) */
export function syncNote(m: Pick<WebinarMonitor, "syncStatus" | "syncedAt" | "syncError"> & { active?: boolean }, now: number) {
  // A stopped webinar is not read from Zoom on purpose: its copy is history, not stale.
  if (m.active === false) return { stale: false, text: m.syncedAt ? `Last read from Zoom ${formatWhen(m.syncedAt, ACADEMY_ZONE)}` : "Not monitored" };
  if (m.syncStatus === "NOT_FOUND") return { stale: true, text: "Zoom no longer finds this webinar; showing the last copy" };
  if (m.syncStatus === "FAILED") return { stale: true, text: "Zoom could not be reached; showing the last copy" };
  if (!m.syncedAt) return { stale: true, text: "Not read from Zoom yet" };
  return { stale: now - Date.parse(m.syncedAt) > 24 * 3600_000, text: `Read from Zoom ${formatWhen(m.syncedAt, ACADEMY_ZONE)}` };
}
