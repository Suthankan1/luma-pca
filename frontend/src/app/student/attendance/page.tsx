import Link from "next/link";
import { Shell } from "@/components/Shell";
import { AttendanceOverview } from "@/components/insights/AttendanceOverview";
import { RangeFilter } from "@/components/insights/RangeFilter";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, presets, withParams, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { academyToday, formatClassTime, formatDuration, outcomeLabels, type AttendanceRecordView } from "@/lib/staff-forms";
import { AppealForm } from "@/components/AppealForm";

export const metadata = { title: "My attendance · LUMA" };

/** The student's own attendance: the API takes the student from the sign-in, so nobody else's can be shown here. */
export default async function MyAttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const query = await searchParams;
  const today = academyToday();
  const fallback = presets(today)[1];
  let from = dateOrNull(query.from) ?? fallback.from;
  let to = dateOrNull(query.to) ?? fallback.to;
  if (from > to) [from, to] = [to, from];
  const [result, records] = await Promise.all([
    studentApi<StudentOverview>(withParams("/api/v1/me/attendance-overview", { from, to }) as `/api/v1/me/${string}`, undefined, 30000),
    studentApi<AttendanceRecordView[]>("/api/v1/me/attendance"),
  ]);
  return <Shell active="student" studentSection="attendance" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <p className="link-row"><Link className="text-link" href="/student">← Student portal</Link></p>
    <h1 className="heading">My attendance</h1>
    {result.ok && <p className="sub"><strong>{result.data.fullName}</strong> · student ID <strong>{result.data.studentNumber}</strong></p>}
    <RangeFilter path="/student/attendance" from={from} to={to} today={today} />
    {result.ok ? <AttendanceOverview overview={result.data} self />
      : <p className="session-error" role="alert">We couldn’t load your attendance right now. Please reload the page shortly.</p>}
    <section className="panel panel-gap" aria-labelledby="my-attendance-title">
      <h2 id="my-attendance-title" className="panel-title">FINAL ATTENDANCE AND APPEALS</h2>
      <p className="panel-sub">Your final attendance for each class, decided by the academy’s rule from Zoom’s report. If something looks
        wrong, send an appeal and the academy will review it.</p>
      {!records.ok ? <p className="session-error" role="alert">We couldn’t load your final attendance right now.</p>
        : <ul className="dir-list">
          {records.data.map((r, index) => <li key={r.id} className="dir-course my-attendance" aria-label={`Attendance ${r.classTitle}`}>
            <div className="dir-row">
              <div><strong>{r.classTitle}</strong>
                <span className="dir-dates muted"> {formatClassTime(r.classStartsAt, r.classEndsAt, r.timeZone)}</span>
                <div className="dir-dates muted">{formatDuration(r.connectedSeconds)} connected
                  {r.appealStatus === "OPEN" ? " · appeal waiting for a decision" : r.appealStatus ? ` · appeal ${r.appealStatus.toLowerCase()}` : ""}</div></div>
              <span className={`status ${r.outcome === "ABSENT" ? "off" : "on"}`}>{outcomeLabels[r.outcome]}</span>
            </div>
            {r.appealStatus !== "OPEN" && <details className="decision"><summary>Appeal this</summary>
              <AppealForm prefix={`appeal-${index}`} recordId={r.id} /></details>}
          </li>)}
          {records.data.length === 0 && <li className="dir-empty muted">No final attendance yet. Classes become final after Zoom’s report
            and the academy’s check.</li>}
        </ul>}
    </section>
  </Shell>;
}
