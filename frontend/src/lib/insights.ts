/** Types and pure helpers for the attendance dashboards (staff and student). Shared by pages, components and unit tests. */

export type Visit = { joinedAt: string; leftAt: string; open: boolean; secondsInClass: number };

export type Cell = { connectedSeconds: number; percent: number; visits: number; outcome: string | null; connectedNow: boolean } | null;

export type ClassSummary = {
  id: string; title: string; startsAt: string; endsAt: string; timeZone: string; batchCodes: string[];
  classSeconds: number; state: string; expected: number; joined: number; averagePercent: number;
};

export type StudentSummary = {
  studentId: string; studentNumber: string; fullName: string; batchCodes: string[]; classes: number; joined: number;
  connectedSeconds: number; classSeconds: number; percent: number; present: number; late: number; absent: number;
  excused: number; rejoins: number; cells: Cell[];
};

export type Dashboard = {
  from: string; to: string; courseId: string | null; batchId: string | null; truncated: boolean;
  totals: { classes: number; students: number; averagePercent: number; classSeconds: number; belowThreshold: number;
    minPercent: number | null; connectedNow: number };
  classes: ClassSummary[]; students: StudentSummary[];
};

export type ClassAttendance = {
  id: string; title: string; startsAt: string; endsAt: string; timeZone: string; batchCodes: string[]; classSeconds: number;
  state: string; connectedSeconds: number; percent: number; visits: number; rejoins: number; connectedNow: boolean;
  firstJoinAt: string | null; secondsAfterStart: number | null; outcome: string | null; timeline: Visit[];
};

export type BatchTotals = {
  batchId: string | null; batchCode: string | null; courseCode: string | null; classes: number; joined: number;
  connectedSeconds: number; classSeconds: number; percent: number; present: number; late: number; absent: number;
  excused: number; rejoins: number;
};

export type Enrolment = {
  batchId: string; batchCode: string; batchName: string; courseId: string; courseCode: string | null; courseTitle: string | null;
  enrolledOn: string; withdrawnOn: string | null; status: string;
};

export type StudentOverview = {
  studentId: string; studentNumber: string; fullName: string; from: string; to: string; minPercent: number | null;
  overall: BatchTotals; byBatch: BatchTotals[]; enrolments: Enrolment[]; classes: ClassAttendance[];
};

/** 0 → "0 min", 1500 → "25 min", 4200 → "1 h 10 min", 7200 → "2 h". Rounded to the minute. */
export function formatDuration(seconds: number): string {
  const minutes = Math.round(Math.max(0, seconds) / 60);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest} min`;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

export function formatClock(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(instant));
}

/** "Mon 9 Nov 2026" in the class's own time zone. */
export function formatDay(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone, weekday: "short", day: "numeric", month: "short", year: "numeric" })
    .format(new Date(instant));
}

/** "9 Nov" for narrow grid headings. */
export function formatShortDay(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone, day: "numeric", month: "short" }).format(new Date(instant));
}

export const stateLabels: Record<string, string> = {
  FINAL: "Final",
  REPORT: "Zoom report",
  ENDED: "Provisional",
  LIVE: "Live now",
  NO_WEBINAR: "No webinar linked",
};

export const outcomeLabels: Record<string, string> = { PRESENT: "Present", LATE: "Late", ABSENT: "Absent", EXCUSED: "Excused" };

/** Colour band for a share of class time: against the academy's rule when there is one, else fixed bands. */
export function level(percent: number, minPercent: number | null, joined = true): "good" | "warn" | "bad" | "none" {
  if (!joined) return "none";
  const target = minPercent ?? 75;
  if (percent >= target) return "good";
  if (percent >= Math.max(0, target - 25)) return "warn";
  return "bad";
}

const datePattern = /^\d{4}-\d{2}-\d{2}$/;

export function dateOrNull(value: unknown): string | null {
  return typeof value === "string" && datePattern.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) ? value : null;
}

function shift(day: string, days: number) {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Quick ranges ending today (in the academy's time zone). */
export function presets(today: string): { key: string; label: string; from: string; to: string }[] {
  return [
    { key: "7", label: "Last 7 days", from: shift(today, -6), to: today },
    { key: "30", label: "Last 30 days", from: shift(today, -29), to: today },
    { key: "month", label: "This month", from: `${today.slice(0, 8)}01`, to: today },
    { key: "90", label: "Last 3 months", from: shift(today, -89), to: today },
  ];
}

/** Builds a link keeping only the parameters that are set. */
export function withParams(path: string, params: Record<string, string | null | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value) query.set(key, value);
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}

/** Positions of each connection on a bar spanning the class time (percent from the left, width), clipped to the class. */
export function timelineSegments(startsAt: string, endsAt: string, visits: Visit[]) {
  const start = Date.parse(startsAt);
  const span = Math.max(1, Date.parse(endsAt) - start);
  return visits.map(v => {
    const from = Math.min(Math.max(Date.parse(v.joinedAt), start), start + span);
    const to = Math.min(Math.max(Date.parse(v.leftAt), start), start + span);
    return { left: ((from - start) / span) * 100, width: Math.max(0, ((to - from) / span) * 100), open: v.open };
  });
}

/** Time away between consecutive connections (ignoring overlaps), for "away 10 min" notes. */
export function gaps(visits: Visit[]): number[] {
  const sorted = [...visits].sort((a, b) => Date.parse(a.joinedAt) - Date.parse(b.joinedAt));
  const result: number[] = [];
  let latestEnd = sorted.length ? Date.parse(sorted[0].leftAt) : 0;
  for (let i = 1; i < sorted.length; i++) {
    const joined = Date.parse(sorted[i].joinedAt);
    result.push(Math.max(0, Math.round((joined - latestEnd) / 1000)));
    latestEnd = Math.max(latestEnd, Date.parse(sorted[i].leftAt));
  }
  return result;
}

/** A student's row without the per-class cells, for the table sent to the browser (the grid needs the cells, the table not). */
export function withoutCells(row: StudentSummary): Omit<StudentSummary, "cells"> {
  const copy: Partial<StudentSummary> = { ...row };
  delete copy.cells;
  return copy as Omit<StudentSummary, "cells">;
}

/** The current time, read once per server render so every class on a page is judged against the same moment. */
export function renderTime(): number {
  return Date.now();
}
