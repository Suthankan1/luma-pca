import { Suspense } from "react";
import { ClassMonthPicker } from "./staff/ClassMonthPicker";
import { PanelRetry } from "./staff/PanelRetry";
import { StudentMonthDetails } from "./StudentMonthDetails";
import { formatDuration, type StudentOverview, type ClassAttendance, type Enrolment } from "@/lib/insights";
import type { ApiResult } from "@/lib/staff-api";
import { classTypeKey, compactClassTitle, monthLabel, type classMonthRange } from "@/lib/staff-home";
import { attendancePercentFor, studentReportSessions, participationFor, type Participation } from "@/lib/student-month";

type Month = ReturnType<typeof classMonthRange>;

type Reports = StudentOverview[] | null | Promise<StudentOverview[] | null>;
export type ProfileClass = { title: string; startsAt: string; localDate: string; status: string; batches: { batchId: string }[] };
type Schedule = Promise<ApiResult<ProfileClass[]>> | null;

export function StudentAttendanceProfile({ range, today, now, reports, schedule, href, staff }: {
  range: Month; today: string; now: number; reports: Reports; schedule: Schedule; href: string; staff?: boolean;
}) {
  return <div className="profile-overview">
    <ClassMonthPicker href={href} month={range.month} currentMonth={today.startsWith(range.month)} label="Attendance month" />
    <Suspense key={range.month} fallback={<StudentProfileSkeleton />}>
      <StudentProfilePanels range={range} now={now} reports={reports} schedule={schedule} staff={staff} />
    </Suspense>
  </div>;
}

export function StudentProfileSkeleton() {
  return <div className="profile-overview" role="status" aria-label="Loading student profile attendance">
    <div className="profile-metrics" aria-hidden="true">
      {[0, 1, 2].map(index => <div key={index} className="panel">
        <div className="skeleton skeleton-line" /><div className="skeleton skeleton-title" />
      </div>)}
    </div>
    <div className="profile-panels" aria-hidden="true">
      <div className="panel"><div className="skeleton skeleton-title" /><div className="skeleton skeleton-card" /></div>
      <div className="panel"><div className="skeleton skeleton-title" /><div className="skeleton skeleton-line" /></div>
    </div>
  </div>;
}

async function StudentProfilePanels({ range, now, reports, schedule, staff }: {
  range: Month; now: number; reports: Reports; schedule: Schedule; staff?: boolean;
}) {
  const loaded = await reports;
  if (!loaded) return <p className="session-error" role="alert">Monthly attendance couldn’t load. <PanelRetry /></p>;
  const { month, first, last, through } = range;
  const enrolments = new Map<string, Enrolment>();
  for (const report of loaded) {
    for (const item of report.enrolments ?? []) {
      if (item.enrolledOn <= last && (!item.withdrawnOn || item.withdrawnOn > first)) {
        enrolments.set(`${item.batchId}/${item.enrolledOn}`, item);
      }
    }
  }
  const sessions = studentReportSessions(loaded, first, through, now);
  const groups = new Map<string, { key: string; title: string; counts: Participation }>();
  const totals = { expected: 0, joined: 0 };
  let connected = 0;
  for (const session of sessions) {
    const key = classTypeKey(session.title);
    const group = groups.get(key) ?? { key, title: compactClassTitle(session.title), counts: { expected: 0, joined: 0 } };
    const { expected, joined } = participationFor(session, session);
    group.counts.expected += expected;
    group.counts.joined += joined;
    totals.expected += expected;
    totals.joined += joined;
    connected += session.connectedSeconds;
    groups.set(key, group);
  }
  const rate = attendancePercentFor(totals);
  return <>
      <dl className="profile-metrics" aria-label={`Attendance summary for ${monthLabel(month)}`}>
        <div className="panel profile-rate"><dt>Average attendance</dt><dd>{rate == null ? "—" : `${Math.round(rate)}%`}</dd></div>
        <div className="panel"><dt>Classes joined</dt><dd>{totals.joined}<small> / {totals.expected}</small></dd></div>
        <div className="panel"><dt>Time connected</dt><dd>{formatDuration(connected)}</dd></div>
      </dl>
      <StudentMonthDetails key={month} types={[...groups.values()]} sessions={sessions} staff={staff} enrolments={
        <Suspense fallback={<p className="panel-sub" role="status">Loading class types…</p>}>
          <EnrolledClassTypes schedule={schedule} enrolments={[...enrolments.values()]} sessions={sessions} range={range} />
        </Suspense>
      } />
  </>;
}

async function EnrolledClassTypes({ schedule, enrolments, sessions, range }: {
  schedule: Schedule; enrolments: Enrolment[]; sessions: ClassAttendance[]; range: Month;
}) {
  const result = await schedule;
  const titles = new Map(sessions.map(session => [classTypeKey(session.title), compactClassTitle(session.title)]));
  const from = Date.parse(range.fromInstant);
  const to = Date.parse(range.toInstant);
  for (const session of result?.ok ? result.data : []) {
    const starts = Date.parse(session.startsAt);
    if (session.status === "CANCELLED" || !(starts >= from && starts < to)) continue;
    if (enrolments.some(item => item.enrolledOn <= session.localDate && (!item.withdrawnOn || item.withdrawnOn > session.localDate)
      && session.batches.some(batch => batch.batchId === item.batchId))) {
      titles.set(classTypeKey(session.title), compactClassTitle(session.title));
    }
  }
  return <>
    {titles.size > 0 && <ul className="profile-enrolments">
      {[...titles].sort((a, b) => a[1].localeCompare(b[1])).map(([key, title]) => <li key={key}><strong>{title}</strong></li>)}
    </ul>}
    {!result?.ok ? <p className="session-error" role="alert">Scheduled class types couldn’t load. <PanelRetry /></p>
      : titles.size === 0 && <p className="panel-sub">No enrolled class types scheduled this month.</p>}
  </>;
}
