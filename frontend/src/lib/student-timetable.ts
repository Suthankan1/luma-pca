import { studentApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, instantParts, isLiveRun } from "@/lib/staff-forms";

export type StudentClass = {
  id: string; title: string; startsAt: string; endsAt: string; timeZone: string;
  status: string; origin?: string | null; joinUrl?: string | null;
  batches: { batchId: string; code: string; name: string }[];
};

type ZoomSlot = {
  id: string; title: string; startsAt: string; durationMinutes: number | null;
  timeZone: string; batchCode: string; joinUrl: string;
};

export type TimetableEntry = {
  id: string; title: string; startsAt: string; endsAt: string | null;
  date: string; cancelled: boolean; estimatedEnd?: boolean; joinUrl?: string | null; batchCodes: string[];
};

export async function studentTimetable(from: string, to: string) {
  const query = new URLSearchParams({ from, to });
  const [classes, schedules] = await Promise.all([
    studentApi<StudentClass[]>(`/api/v1/me/class-sessions?${query}`),
    studentApi<ZoomSlot[]>(`/api/v1/me/webinar-schedule?${query}`),
  ]);
  const sessions = classes.ok ? classes.data : [];
  const dateFor = (instant: string) => instantParts(instant, ACADEMY_TIME_ZONE).date;
  const entries: TimetableEntry[] = sessions.map(c => ({
    id: c.id, title: c.title, startsAt: c.startsAt, endsAt: c.endsAt, estimatedEnd: isLiveRun(c),
    date: dateFor(c.startsAt), cancelled: c.status === "CANCELLED", joinUrl: c.joinUrl,
    batchCodes: c.batches.map(b => b.code),
  }));
  for (const slot of schedules.ok ? schedules.data : []) {
    const start = Date.parse(slot.startsAt);
    if (sessions.some(c => c.status !== "CANCELLED" && c.joinUrl === slot.joinUrl && Date.parse(c.startsAt) === start)) continue;
    entries.push({
      id: slot.id, title: slot.title, startsAt: slot.startsAt,
      endsAt: slot.durationMinutes ? new Date(start + slot.durationMinutes * 60_000).toISOString() : null,
      date: dateFor(slot.startsAt), cancelled: false, joinUrl: slot.joinUrl, batchCodes: [slot.batchCode],
    });
  }
  entries.sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt));
  return { entries, sessions, unavailable: !classes.ok || !schedules.ok };
}
