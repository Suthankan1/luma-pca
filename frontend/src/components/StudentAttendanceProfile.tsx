import { ClassMonthPicker } from "./staff/ClassMonthPicker";
import { StudentMonthDetails } from "./StudentMonthDetails";
import { formatDuration, type StudentOverview, type ClassAttendance, type Enrolment } from "@/lib/insights";
import { formatDate, instantParts, ACADEMY_TIME_ZONE } from "@/lib/staff-forms";
import { classTypeKey, compactClassTitle, monthLabel, type classMonthRange } from "@/lib/staff-home";
import { attendancePercentFor, completedForStudent, participationFor, type Participation } from "@/lib/student-month";

type Month = ReturnType<typeof classMonthRange>;

export function StudentAttendanceProfile({ range, today, now, reports, href, staff }: {
  range: Month; today: string; now: number; reports: StudentOverview[] | null; href: string; staff?: boolean;
}) {
  const { month, first, last, through } = range;
  const classes = new Map<string, ClassAttendance>();
  const enrolments = new Map<string, Enrolment>();
  for (const report of reports ?? []) {
    for (const session of report.classes) {
      const day = instantParts(session.startsAt, ACADEMY_TIME_ZONE).date;
      if (through && day >= first && day <= through && completedForStudent(session, now)) classes.set(session.id, session);
    }
    for (const item of report.enrolments ?? []) {
      if (item.enrolledOn <= last && (!item.withdrawnOn || item.withdrawnOn > first)) enrolments.set(item.batchId, item);
    }
  }
  const sessions = [...classes.values()].sort((a, b) => b.startsAt.localeCompare(a.startsAt));
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
  return <div className="profile-overview">
    <ClassMonthPicker href={href} month={month} currentMonth={today.startsWith(month)} label="Attendance month" />
    {!reports ? <p className="session-error" role="alert">Monthly attendance couldn’t load.</p> : <>
      <dl className="profile-metrics" aria-label={`Attendance summary for ${monthLabel(month)}`}>
        <div className="panel profile-rate"><dt>Average attendance</dt><dd>{rate == null ? "—" : `${Math.round(rate)}%`}</dd></div>
        <div className="panel"><dt>Classes joined</dt><dd>{totals.joined}<small> / {totals.expected}</small></dd></div>
        <div className="panel"><dt>Time connected</dt><dd>{formatDuration(connected)}</dd></div>
        <div className="panel"><dt>Enrolled batches</dt><dd>{enrolments.size}</dd></div>
      </dl>
      <StudentMonthDetails key={month} types={[...groups.values()]} sessions={sessions} staff={staff} enrolments={<>
        {enrolments.size ? <ul className="profile-enrolments">{[...enrolments.values()].map(item => <li key={item.batchId}>
          <strong>{item.batchName}</strong>
          <span>{[item.batchCode, item.courseTitle].filter(Boolean).join(" · ")}</span>
          <small>{formatDate(item.enrolledOn)}{item.withdrawnOn ? ` – ${formatDate(item.withdrawnOn)}` : " onward"}</small>
        </li>)}</ul> : <p className="panel-sub">No enrolments in {monthLabel(month)}.</p>}
      </>} />
    </>}
  </div>;
}
