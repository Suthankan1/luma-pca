/** Pure helpers for the staff home: the chosen day and month, the calendar grid, what runs when, and live numbers. Unit-tested. */
import { addDays, ACADEMY_TIME_ZONE, instantParts, isLiveRun, isRealDate, zonedToInstant,
  type AttendanceRow, type ClassSession } from "./staff-forms.ts";
import type { WebinarMonitor } from "./webinars.ts";

const monthShape = /^\d{4}-(0[1-9]|1[0-2])$/;
const monthNames = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const weekdayNames = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export function isMonthKey(value: unknown): value is string {
  return typeof value === "string" && monthShape.test(value);
}

/** The day shown in the day list: `?day=` when it names a real date, else today. */
export function chosenDay(param: unknown, today: string) {
  return isRealDate(param) ? param : today;
}

/** The month shown in the calendar: `?month=` when valid, else the chosen day's month. */
export function chosenMonth(param: unknown, day: string) {
  return isMonthKey(param) ? param : day.slice(0, 7);
}

export function shiftMonth(month: string, by: number) {
  const [y, m] = month.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1 + by, 1));
  return date.toISOString().slice(0, 7);
}

/** "November 2026". */
export function monthLabel(month: string) {
  const [y, m] = month.split("-").map(Number);
  return `${monthNames[m - 1]} ${y}`;
}

/** 0 = Monday … 6 = Sunday, for a "YYYY-MM-DD" date. */
export function weekdayIndex(day: string) {
  const [y, m, d] = day.split("-").map(Number);
  return (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7;
}

/** "Friday 20 November 2026". */
export function longDayLabel(day: string) {
  const [y, m, d] = day.split("-").map(Number);
  return `${weekdayNames[weekdayIndex(day)]} ${d} ${monthNames[m - 1]} ${y}`;
}

/** "Today", "Tomorrow", "Yesterday", or the weekday for any other day. */
export function relativeDayName(day: string, today: string) {
  if (day === today) return "Today";
  if (day === addDays(today, 1)) return "Tomorrow";
  if (day === addDays(today, -1)) return "Yesterday";
  return weekdayNames[weekdayIndex(day)];
}

export type CalendarDay = { date: string; inMonth: boolean };

/** Whole Monday-to-Sunday weeks covering the month, including the neighbouring months' days that complete them. */
export function monthWeeks(month: string): CalendarDay[][] {
  const first = `${month}-01`;
  const start = addDays(first, -weekdayIndex(first));
  const next = `${shiftMonth(month, 1)}-01`;
  const lastDay = addDays(next, -1);
  const end = addDays(lastDay, 6 - weekdayIndex(lastDay));
  const weeks: CalendarDay[][] = [];
  for (let day = start; day <= end; day = addDays(day, 7)) {
    weeks.push(Array.from({ length: 7 }, (_, i) => {
      const date = addDays(day, i);
      return { date, inMonth: date.startsWith(month) };
    }));
  }
  return weeks;
}

/** The instants the calendar's whole grid covers, for one request (at most 42 days, within the API's 92-day limit). */
export function gridRange(month: string) {
  const weeks = monthWeeks(month);
  const first = weeks[0][0].date;
  const after = addDays(weeks[weeks.length - 1][6].date, 1);
  return { first, after, fromInstant: zonedToInstant(first, "00:00", ACADEMY_TIME_ZONE),
    toInstant: zonedToInstant(after, "00:00", ACADEMY_TIME_ZONE) };
}

export type EntryState = "live" | "now" | "upcoming" | "ended";

/** One thing on the timetable: a LUMA class, or a Zoom occurrence that has no class yet. */
export type DayEntry = {
  key: string; sessionId: string | null; title: string; startsAt: string; endsAt: string | null; timeZone: string;
  source: "class" | "zoom"; state: EntryState; href: string;
};

export const entryStateLabels: Record<EntryState, string> = { live: "Live", now: "In progress", upcoming: "Upcoming", ended: "Ended" };

/** Within this much of a class's start, a Zoom occurrence of the same webinar is that class, not another one. */
const SAME_RUN_MS = 60 * 60_000;

/**
 * The timetable by academy date, soonest first: scheduled classes, plus
 * Zoom's own upcoming occurrences that no class covers yet. `liveIds` are classes Zoom reports live right now.
 */
export function calendarEntries(sessions: readonly ClassSession[], monitors: readonly WebinarMonitor[] | null,
                                liveIds: ReadonlySet<string>, now: number): Map<string, DayEntry[]> {
  const byDay = new Map<string, DayEntry[]>();
  const add = (day: string, entry: DayEntry) => byDay.set(day, [...(byDay.get(day) ?? []), entry]);
  const scheduled = sessions.filter(s => s.status === "SCHEDULED");
  for (const s of scheduled) {
    const liveRun = isLiveRun(s, now);
    const state: EntryState = liveIds.has(s.id) || liveRun ? "live"
      : now < Date.parse(s.startsAt) ? "upcoming" : now < Date.parse(s.endsAt) ? "now" : "ended";
    add(s.localDate, { key: s.id, sessionId: s.id, title: s.title, startsAt: s.startsAt, endsAt: liveRun ? null : s.endsAt,
      timeZone: s.timeZone, source: "class", state, href: `/staff/sessions/${s.id}` });
  }
  for (const m of monitors ?? []) {
    if (!m.active) continue;
    for (const o of m.occurrences) {
      if (!o.startTime || o.status === "deleted") continue;
      const start = Date.parse(o.startTime);
      const covered = scheduled.some(s => s.zoomWebinarId === m.webinarId && Math.abs(Date.parse(s.startsAt) - start) <= SAME_RUN_MS);
      if (covered) continue;
      const minutes = o.durationMinutes ?? m.durationMinutes ?? 60;
      const endsAt = new Date(start + minutes * 60_000).toISOString();
      add(instantParts(o.startTime, ACADEMY_TIME_ZONE).date, { key: `${m.webinarId}-${o.occurrenceId ?? o.startTime}`, sessionId: null,
        title: m.topic ?? "Zoom webinar", startsAt: o.startTime, endsAt, timeZone: m.timeZone ?? ACADEMY_TIME_ZONE, source: "zoom",
        state: now < start ? "upcoming" : now < Date.parse(endsAt) ? "now" : "ended", href: "/staff/sessions" });
    }
  }
  for (const entries of byDay.values()) entries.sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt));
  return byDay;
}

/** The next entry still to start, across the days given (already sorted within each day). */
export function nextEntry(byDay: ReadonlyMap<string, DayEntry[]>, now: number) {
  return [...byDay.values()].flat().filter(e => e.state === "upcoming" && Date.parse(e.startsAt) > now)
    .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))[0] ?? null;
}

/** "18:00–20:00", or "from 18:00" when the end is not known yet (a live Zoom run). */
export function timeRange(entry: Pick<DayEntry, "startsAt" | "endsAt" | "timeZone">) {
  const start = instantParts(entry.startsAt, entry.timeZone).time;
  return entry.endsAt ? `${start}–${instantParts(entry.endsAt, entry.timeZone).time}` : `from ${start}`;
}

function minutesText(totalMinutes: number) {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} min`;
  return minutes === 0 ? `${hours} h` : `${hours} h ${minutes} min`;
}

/** "starts in 1 h 20 min", "starts in 5 min", "starting now"; days ahead as "starts in 2 days". */
export function startsInLabel(startsAt: string, now: number) {
  const minutes = Math.ceil((Date.parse(startsAt) - now) / 60_000);
  if (minutes <= 1) return "starting now";
  if (minutes >= 48 * 60) return `starts in ${Math.floor(minutes / (24 * 60))} days`;
  return `starts in ${minutesText(minutes)}`;
}

/** "started 25 min ago", "started 1 h 5 min ago". */
export function startedAgoLabel(startsAt: string, now: number) {
  const minutes = Math.max(0, Math.floor((now - Date.parse(startsAt)) / 60_000));
  return minutes < 1 ? "just started" : `started ${minutesText(minutes)} ago`;
}

export type LiveNumbers = { expected: number; joined: number; connectedNow: number; percent: number | null };

/**
 * A running class in numbers: students expected (enrolled in its batches), how many of them have joined so far, how many
 * people are connected now (Zoom's count when known, else the students with an open visit), and joined as a share of expected.
 */
export function liveNumbers(rows: readonly AttendanceRow[], zoomConnectedNow: number | null = null): LiveNumbers {
  const expected = rows.filter(r => r.expected).length;
  const joined = rows.filter(r => r.expected && r.seen).length;
  const connectedNow = zoomConnectedNow ?? rows.filter(r => r.openVisits > 0).length;
  return { expected, joined, connectedNow, percent: expected ? Math.round((joined * 100) / expected) : null };
}

/** "18 of 24 joined · 75%", from the per-class attendance totals. */
export function joinedLabel(c: { expected: number; joined: number }) {
  if (!c.expected) return c.joined ? `${c.joined} joined` : "No students expected";
  return `${c.joined} of ${c.expected} joined · ${Math.round((c.joined * 100) / c.expected)}%`;
}

/** "Good morning" before noon, "Good afternoon" until five, then "Good evening", at the academy. */
export function greeting(now: number) {
  const hour = Number(instantParts(new Date(now).toISOString(), ACADEMY_TIME_ZONE).time.slice(0, 2));
  return hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}

/** The home page's own link: the day and month shown, without defaults that would only repeat today. */
export function homeHref(options: { day?: string | null; month?: string | null }, today: string) {
  const params = new URLSearchParams();
  if (options.day && options.day !== today) params.set("day", options.day);
  if (options.month && options.month !== (options.day ?? today).slice(0, 7)) params.set("month", options.month);
  const query = params.toString();
  return `/staff${query ? `?${query}` : ""}`;
}

/** The days of a month that have already started at the academy, or null for a future month. */
export function heldRange(month: string, today: string) {
  const first = `${month}-01`;
  if (first > today) return null;
  const last = addDays(`${shiftMonth(month, 1)}-01`, -1);
  return { from: first, to: last < today ? last : today };
}

export type ClassAttendanceTotal = { id: string; expected: number; joined: number };

/**
 * Average the unrounded share of expected students who joined, giving each class equal weight.
 * Classes without expected students have no rate.
 */
export function monthlyAttendance(totals: readonly Pick<ClassAttendanceTotal, "expected" | "joined">[]) {
  const counted = totals.filter(c => c.expected > 0);
  const sum = counted.reduce((total, c) => total + (Math.min(c.joined, c.expected) * 100) / c.expected, 0);
  const average = counted.length ? Math.round(sum / counted.length) : null;
  return { average, classes: counted.length, withoutStudents: totals.length - counted.length };
}

/** "Fri 20 Nov". */
export function shortDayLabel(day: string) {
  const [, m, d] = day.split("-").map(Number);
  return `${weekdayNames[weekdayIndex(day)].slice(0, 3)} ${d} ${monthNames[m - 1].slice(0, 3)}`;
}
