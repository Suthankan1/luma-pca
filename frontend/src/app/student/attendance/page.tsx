import { Shell } from "@/components/Shell";
import { StudentAttendanceProfile } from "@/components/StudentAttendanceProfile";
import { AppealForm } from "@/components/AppealForm";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, renderTime, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { academyToday, formatClassTime, formatDuration, outcomeLabels, instantParts, ACADEMY_TIME_ZONE,
  type AttendanceRecordView } from "@/lib/staff-forms";
import { classMonthRange, monthLabel } from "@/lib/staff-home";
import { readAttendanceWindows } from "@/lib/student-month-api";

export const metadata = { title: "My attendance · LUMA" };

export default async function MyAttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const query = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange(query.month, today, dateOrNull(query.from));
  const [me, reports, records] = await Promise.all([
    studentApi<{ fullName: string; studentNumber: string }>("/api/v1/me/student"),
    readAttendanceWindows(range.first, range.through ?? range.last, (from, to) => studentApi<StudentOverview>(
      `/api/v1/me/attendance-overview?${new URLSearchParams({ from, to })}`, undefined, 30000), data => data.classes.length >= 80),
    studentApi<AttendanceRecordView[]>("/api/v1/me/attendance"),
  ]);
  const finalRecords = records.ok ? records.data.filter(record => {
    const day = instantParts(record.classStartsAt, ACADEMY_TIME_ZONE).date;
    return day >= range.first && day <= range.last;
  }) : [];
  return <Shell active="student" studentSection="attendance" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <header className="page-head"><div><p className="crumbs">Student portal / My attendance</p>
      <h1 className="heading staff-heading">{me.ok ? me.data.fullName : "My attendance"}</h1>
      <p className="sub page-sub">{me.ok ? `${me.data.studentNumber} · ` : ""}{monthLabel(range.month)}</p>
    </div></header>
    <StudentAttendanceProfile range={range} today={today} now={now} reports={reports} href="/student" />
    {!records.ok && <p className="session-error panel-gap" role="alert">Attendance appeals couldn’t load.</p>}
    {finalRecords.length > 0 && <details className="panel panel-gap profile-appeals">
      <summary>Attendance appeals</summary>
      <ul className="dir-list">{finalRecords.map((record, index) => <li key={record.id} className="dir-course my-attendance" aria-label={`Attendance ${record.classTitle}`}>
        <div className="dir-row"><div><strong>{record.classTitle}</strong>
          <span className="dir-dates muted"> {formatClassTime(record.classStartsAt, record.classEndsAt, record.timeZone)}</span>
          <div className="dir-dates muted">{formatDuration(record.connectedSeconds)} connected
            {record.appealStatus === "OPEN" ? " · appeal waiting for a decision" : record.appealStatus ? ` · appeal ${record.appealStatus.toLowerCase()}` : ""}</div></div>
          <span className={`status ${record.outcome === "ABSENT" ? "off" : "on"}`}>{outcomeLabels[record.outcome]}</span>
        </div>
        {record.appealStatus !== "OPEN" && <details className="decision"><summary>Appeal this</summary>
          <AppealForm prefix={`appeal-${index}`} recordId={record.id} /></details>}
      </li>)}</ul>
    </details>}
  </Shell>;
}
