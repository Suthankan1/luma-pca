import { formatClock, formatDuration, outcomeLabels, type ClassAttendance } from "./insights";
import type { ReportPeriod } from "./report-period";
import type { StaffReportPdf } from "./staff-report-pdf";
import { ACADEMY_TIME_ZONE, formatDate, instantParts, type ClassSession, type ProvisionalAttendance } from "./staff-forms";
import { averageClassAttendance, classTypeKey, compactClassTitle, monthLabel, type ClassAttendanceTotal } from "./staff-home";
import { attendancePercentFor, participationFor, type StudentDirectoryEntry } from "./student-month";

type ReportTable = StaffReportPdf["tables"][number];
type Counts = { expected: number; joined: number };
type CalendarEntry = { date: string; joined?: boolean };

const metric = (label: string, value: string | number) => ({ label, value: String(value) });
const column = (label: string, weight: number, align?: "left" | "right" | "center") => ({ label, weight, align });
const percent = (value: number | null) => value == null ? "N/A" : `${Math.round(value)}%`;
const rate = (counts: Counts) => percent(attendancePercentFor(counts));
const clock = (instant: string) => formatClock(instant, ACADEMY_TIME_ZONE);
const local = (instant: string) => instantParts(instant, ACADEMY_TIME_ZONE);
const reportDates = (period: ReportPeriod) => `${formatDate(period.from)} to ${formatDate(period.to)} | Sri Lanka time`;
const timeRange = (start: string, end: string) => `${clock(start)} - ${clock(end)}`;

export function reportAttendanceStatus(outcome: string | null | undefined, recorded: boolean, joined: boolean) {
  return outcome ? outcomeLabels[outcome] ?? outcome : !recorded ? "Unavailable" : joined ? "Joined · pending" : "No recorded join · pending";
}

function calendar(months: string[], entries: CalendarEntry[]): StaffReportPdf["calendar"] {
  const days = new Map<string, { date: string; count: number; missed: boolean }>();
  for (const entry of entries) {
    const day = days.get(entry.date) ?? { date: entry.date, count: 0, missed: false };
    day.count++;
    day.missed ||= entry.joined === false;
    days.set(entry.date, day);
  }
  const byMonth = new Map(months.map(month => [month, [] as StaffReportPdf["calendar"][number]["days"]]));
  for (const { date, count, missed } of days.values()) {
    byMonth.get(date.slice(0, 7))?.push({ date, count, accent: missed ? "warning" : "neutral" });
  }
  return Array.from(byMonth, ([month, days]) => ({ month, days }));
}

export function sessionReportPdf(period: ReportPeriod, session: ClassSession, attendance: ProvisionalAttendance): StaffReportPdf {
  const expected = attendance.students.filter(row => row.expected);
  const joined = expected.filter(row => row.seen);
  const evidence = attendance.linkedOccurrences > 0 || attendance.students.some(row => row.seen);
  const date = local(attendance.classStartsAt).date;
  const finalStatus = attendance.finalisedAt ? "Final" : attendance.status === "RECONCILED" ? "Reconciled" : "Provisional";
  const totalConnected = joined.reduce((sum, row) => sum + row.connectedSeconds, 0);
  return {
    title: "Class session attendance",
    subject: compactClassTitle(session.title),
    periodLabel: `${formatDate(date)} | ${timeRange(attendance.classStartsAt, attendance.classEndsAt)} | Sri Lanka time`,
    metrics: [metric("Expected students", expected.length), metric("Expected students joined", joined.length),
      metric("Join rate", evidence ? rate({ expected: expected.length, joined: joined.length }) : "N/A"),
      metric("Average time per expected joiner", joined.length ? formatDuration(totalConnected / joined.length) : "N/A")],
    notes: [
      `Selection period: ${reportDates(period)}. Attendance status: ${finalStatus}. ${attendance.reportedOccurrences} of ${attendance.linkedOccurrences} linked Zoom occurrences reconciled.`,
      `Other matched students who joined: ${attendance.students.filter(row => !row.expected && row.seen).length}. Unmatched Zoom participants: ${attendance.unmatchedPeople}.`,
      "The join rate uses the expected roster. A missing join record has no final attendance outcome until it is recorded.",
    ],
    calendar: calendar([date.slice(0, 7)], [{ date }]),
    tables: [{
      title: "Student attendance roster",
      columns: [column("Registration ID", 1.12), column("Student", 1.62), column("Expected", .68, "center"),
        column("First join", .75), column("Visits", .55, "right"), column("Connected", .9, "right"), column("Outcome", 1.38)],
      rows: attendance.students.map(row => [
        row.studentNumber, row.fullName, row.expected ? "Yes" : "No",
        row.firstJoinAt ? clock(row.firstJoinAt) : "-",
        row.seen || evidence ? String(row.visits) : "-",
        row.seen || evidence || row.outcome ? formatDuration(row.connectedSeconds) : "-",
        reportAttendanceStatus(row.outcome, row.seen || evidence, row.seen),
      ]),
    }],
  };
}

export function classTypeReportPdf(period: ReportPeriod, sessions: ClassSession[], totals: Map<string, ClassAttendanceTotal>): StaffReportPdf {
  const timeOrdered = [...sessions].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  const monthly = new Map<string, { sessions: number; expected: number; joined: number }>();
  let expected = 0;
  let joined = 0;
  const rows = timeOrdered.map(session => {
    const counts = totals.get(session.id)!;
    const date = local(session.startsAt).date;
    const month = date.slice(0, 7);
    const group = monthly.get(month) ?? { sessions: 0, expected: 0, joined: 0 };
    group.sessions++;
    group.expected += counts.expected;
    group.joined += counts.joined;
    monthly.set(month, group);
    expected += counts.expected;
    joined += counts.joined;
    return [date, timeRange(session.startsAt, session.endsAt), compactClassTitle(session.title),
      String(counts.expected), String(counts.joined), rate(counts)];
  });
  return {
    title: "Class type attendance",
    subject: compactClassTitle(timeOrdered[0].title),
    periodLabel: reportDates(period),
    metrics: [metric("Completed sessions", sessions.length), metric("Expected places", expected),
      metric("Places joined", joined), metric("Overall join rate", rate({ expected, joined }))],
    notes: [
      `Average of individual session join rates: ${percent(averageClassAttendance(sessions, totals))}.`,
      "Overall join rate divides recorded joins by expected places across all sessions. A student expected in multiple sessions contributes one place per session.",
    ],
    calendar: calendar(period.months.map(item => item.month), timeOrdered.map(session => ({ date: local(session.startsAt).date }))),
    tables: [{
      title: "Monthly breakdown",
      columns: [column("Month", 2), column("Sessions", 1, "right"), column("Expected places", 1.3, "right"),
        column("Joined", 1, "right"), column("Join rate", 1, "right")],
      rows: period.months.map(({ month }) => {
        const summary = monthly.get(month) ?? { sessions: 0, expected: 0, joined: 0 };
        return [monthLabel(month), String(summary.sessions), String(summary.expected), String(summary.joined), rate(summary)];
      }),
    }, {
      title: "Completed sessions",
      columns: [column("Date", 1.05), column("Start - end", 1.05), column("Class", 2.25),
        column("Expected", .8, "right"), column("Joined", .65, "right"), column("Join rate", .8, "right")],
      rows,
    }],
  };
}

export function studentReportPdf(period: ReportPeriod, student: StudentDirectoryEntry, sessions: ClassAttendance[]): StaffReportPdf {
  const byType = new Map<string, Counts & { title: string; seconds: number }>();
  const totals = { expected: 0, joined: 0 };
  const outcomes = new Map<string, number>();
  const days: CalendarEntry[] = [];
  const visits: string[][] = [];
  let seconds = 0;
  const rows = sessions.map(session => {
    const title = compactClassTitle(session.title);
    const type = classTypeKey(session.title);
    const { expected, joined } = participationFor(session, session);
    const { date } = local(session.startsAt);
    const group = byType.get(type) ?? { title, expected: 0, joined: 0, seconds: 0 };
    group.expected += expected;
    group.joined += joined;
    group.seconds += session.connectedSeconds;
    byType.set(type, group);
    totals.expected += expected;
    totals.joined += joined;
    seconds += session.connectedSeconds;
    if (session.outcome) outcomes.set(session.outcome, (outcomes.get(session.outcome) ?? 0) + 1);
    days.push({ date, joined: expected ? joined > 0 : undefined });
    for (const visit of session.timeline) {
      visits.push([date, title, clock(visit.joinedAt), visit.open ? "Open" : clock(visit.leftAt), formatDuration(visit.secondsInClass)]);
    }
    return [date, timeRange(session.startsAt, session.endsAt), title,
      expected ? `${formatDuration(session.connectedSeconds)} / ${formatDuration(session.classSeconds)}` : "-",
      expected ? String(session.visits) : "-",
      reportAttendanceStatus(session.outcome, expected > 0, joined > 0)];
  });
  const tables: ReportTable[] = [{
    title: "Class type breakdown",
    columns: [column("Class type", 2.4), column("Tracked sessions", 1, "right"), column("Joined", .7, "right"),
      column("Join rate", .8, "right"), column("Connected", 1.1, "right")],
    rows: [...byType.values()].map(group => [group.title, String(group.expected), String(group.joined), rate(group), formatDuration(group.seconds)]),
  }, {
    title: "Completed sessions",
    columns: [column("Date", 1), column("Start - end", 1.02), column("Class type", 2.15),
      column("Connected / class", 1.2, "right"), column("Visits", .5, "right"), column("Outcome", 1.43)],
    rows,
  }];
  if (visits.length) tables.push({
    title: "Connection history",
    columns: [column("Class date", 1), column("Class type", 2.6),
      column("Joined at", .8), column("Left at", .8), column("Time in class", 1, "right")],
    rows: visits,
  });
  return {
    title: "Student attendance",
    subject: `${student.fullName} | ${student.studentNumber}`,
    periodLabel: reportDates(period),
    metrics: [metric("Tracked sessions", totals.expected), metric("Sessions joined", totals.joined),
      metric("Join rate", rate(totals)), metric("Total connected time", formatDuration(seconds))],
    notes: [
      `Final outcomes: ${Object.entries(outcomeLabels).map(([key, label]) => `${label} ${outcomes.get(key) ?? 0}`).join(" | ")}.`,
      "An amber calendar day has at least one tracked session with no recorded join. Sessions without attendance evidence are shown as unavailable.",
    ],
    calendar: calendar(period.months.map(item => item.month), days),
    tables,
  };
}
