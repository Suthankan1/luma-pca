import { Shell } from "@/components/Shell";
import { StudentAttendanceProfile, type ProfileClass } from "@/components/StudentAttendanceProfile";
import { AppealForm } from "@/components/AppealForm";
import { ConsentForm } from "@/components/StudentEngagement";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, renderTime, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { academyToday, formatClassTime, formatDate, formatDuration, outcomeLabels, instantParts, ACADEMY_TIME_ZONE,
  type AttendanceRecordView, type MyConsent } from "@/lib/staff-forms";
import { classMonthRange, compactClassTitle, monthLabel } from "@/lib/staff-home";
import { readAttendanceWindows } from "@/lib/student-month-api";

export const metadata = { title: "My Report · LUMA" };

export default async function MyAttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const query = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange(query.month, today, dateOrNull(query.from));
  const scheduleRange = new URLSearchParams({ from: range.fromInstant, to: range.toInstant });
  const schedule = studentApi<ProfileClass[]>(`/api/v1/me/class-sessions?${scheduleRange}`, undefined, 25000);
  const [me, reports, records, consent, enrollments] = await Promise.all([
    studentApi<{ fullName: string; studentNumber: string }>("/api/v1/me/student"),
    readAttendanceWindows(range.first, range.through ?? range.last, (from, to) => studentApi<StudentOverview>(
      `/api/v1/me/attendance-overview?${new URLSearchParams({ from, to })}`, undefined, 30000), data => data.classes.length >= 80),
    studentApi<AttendanceRecordView[]>("/api/v1/me/attendance"),
    studentApi<MyConsent>("/api/v1/me/engagement-consent"),
    studentApi<{ id: string; batchCode: string; batchName: string; enrolledOn: string; withdrawnOn: string | null }[]>("/api/v1/me/enrollments"),
  ]);
  const finalRecords = records.ok ? records.data.filter(record => {
    const day = instantParts(record.classStartsAt, ACADEMY_TIME_ZONE).date;
    return day >= range.first && day <= range.last;
  }) : [];
  return <Shell active="student" studentSection="attendance" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <header className="page-head"><div>
      <h1 className="heading staff-heading">My Report</h1>
      <p className="sub page-sub">{me.ok ? `${me.data.fullName} · ${me.data.studentNumber} · ` : ""}{monthLabel(range.month)}</p>
    </div></header>
    <div className="student-portal-enter">
      <StudentAttendanceProfile range={range} today={today} now={now} reports={reports} schedule={schedule} href="/student" />
    </div>
    {!records.ok && <p className="session-error panel-gap" role="alert">Attendance appeals couldn’t load.</p>}
    {finalRecords.length > 0 && <details className="panel panel-gap profile-appeals">
      <summary>Attendance appeals</summary>
      <ul className="dir-list">{finalRecords.map((record, index) => <li key={record.id} className="dir-course my-attendance" aria-label={`Attendance ${compactClassTitle(record.classTitle)}`}>
        <div className="dir-row"><div><strong>{compactClassTitle(record.classTitle)}</strong>
          <span className="dir-dates muted"> {formatClassTime(record.classStartsAt, record.classEndsAt, record.timeZone)}</span>
          <div className="dir-dates muted">{formatDuration(record.connectedSeconds)} connected
            {record.appealStatus === "OPEN" ? " · appeal waiting for a decision" : record.appealStatus ? ` · appeal ${record.appealStatus.toLowerCase()}` : ""}</div></div>
          <span className={`status ${record.outcome === "ABSENT" ? "off" : "on"}`}>{outcomeLabels[record.outcome]}</span>
        </div>
        {record.appealStatus !== "OPEN" && <details className="decision"><summary>Appeal this</summary>
          <AppealForm prefix={`appeal-${index}`} recordId={record.id} /></details>}
      </li>)}</ul>
    </details>}
    {consent.ok && consent.data.enabled && <section className="panel panel-gap" aria-labelledby="engagement-title">
      <h2 id="engagement-title" className="panel-title">Reactions and questions</h2>
      <ConsentForm consent={consent.data} />
    </section>}
    {enrollments.ok && <details className="panel panel-gap profile-appeals">
      <summary>My batches</summary>
      {enrollments.data.length ? <ul className="profile-enrolments">
        {enrollments.data.map(batch => <li key={batch.id}><span><strong>{batch.batchCode}</strong> {batch.batchName}
          {batch.withdrawnOn && ` · Withdrawn ${formatDate(batch.withdrawnOn)}`}</span>
          <span className="panel-sub">Enrolled from {formatDate(batch.enrolledOn)}</span></li>)}
      </ul> : <p className="panel-sub">You aren’t enrolled in a batch yet. The academy office can enrol you.</p>}
    </details>}
  </Shell>;
}
