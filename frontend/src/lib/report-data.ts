import type { ReportPeriod } from "./report-period";
import { staffApi } from "./staff-api";
import { ACADEMY_TIME_ZONE, instantParts, type ClassSession } from "./staff-forms";
import type { ClassAttendanceTotal } from "./staff-home";
import type { StudentOverview } from "./insights";
import { readAttendanceWindows } from "./student-month-api";

export async function reportSessions(period: ReportPeriod, now: number) {
  const sessions: ClassSession[] = [];
  for (const { fromInstant, toInstant } of period.months) {
    const query = new URLSearchParams({ from: fromInstant, to: toInstant });
    const result = await staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`, undefined, 25000);
    if (!result.ok || !result.data) return null;
    sessions.push(...result.data.filter(session => session.status === "SCHEDULED" && Date.parse(session.endsAt) <= now));
  }
  return sessions.sort((a, b) => Date.parse(b.startsAt) - Date.parse(a.startsAt));
}

export async function reportClassTotals(period: ReportPeriod, sessions: ClassSession[]) {
  const totals = new Map<string, ClassAttendanceTotal>();
  for (const { month, first, through } of period.months) {
    if (!through || !sessions.some(session => instantParts(session.startsAt, ACADEMY_TIME_ZONE).date.startsWith(month))) continue;
    const result = await staffApi<ClassAttendanceTotal[]>(`/api/v1/attendance/class-totals?${new URLSearchParams({ from: first, to: through })}`,
      undefined, 25000);
    if (!result.ok || !result.data) return null;
    for (const entry of result.data) totals.set(entry.id, entry);
  }
  return totals;
}

export async function reportStudentSessions(studentId: string, period: ReportPeriod) {
  const classrooms: StudentOverview["classes"] = [];
  for (const { first, through } of period.months) {
    if (!through) continue;
    const reports = await readAttendanceWindows(first, through, (from, to) => staffApi<StudentOverview>(
      `/api/v1/students/${studentId}/attendance-overview?${new URLSearchParams({ from, to })}`, undefined, 30000),
    result => result.classes.length >= 80);
    if (!reports) return null;
    for (const report of reports) classrooms.push(...report.classes);
  }
  return classrooms;
}
