import Link from "next/link";
import { Shell } from "@/components/Shell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { AppealForm } from "@/components/AppealForm";
import { ConsentForm } from "@/components/StudentEngagement";
import { formatClassTime, formatDate, classOpenForEngagement, formatDuration, outcomeLabels, sessionWindow, type AttendanceRecordView, type MyConsent } from "@/lib/staff-forms";

type MyEnrollment = { id: string; batchCode: string; batchName: string; enrolledOn: string; withdrawnOn: string | null; status: string };
type MyClass = { id: string; title: string; startsAt: string; endsAt: string; timeZone: string; localDate: string; status: string;
  batches: { batchId: string; code: string; name: string }[] };

const WINDOW_DAYS = 28;

export const metadata = { title: "Student portal · LUMA" };

export default async function StudentPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const params = await searchParams;
  const window = sessionWindow(params.from, undefined, WINDOW_DAYS);
  const href = (from?: string) => from ? `/student?from=${from}` : "/student";
  const query = new URLSearchParams({ from: window.fromInstant, to: window.toInstant });
  const [me, enrollments, classes, attendance, consent] = await Promise.all([
    studentApi<{ studentNumber: string; fullName: string }>("/api/v1/me/student"),
    studentApi<MyEnrollment[]>("/api/v1/me/enrollments"),
    studentApi<MyClass[]>(`/api/v1/me/class-sessions?${query}`),
    studentApi<AttendanceRecordView[]>("/api/v1/me/attendance"),
    studentApi<MyConsent>("/api/v1/me/engagement-consent"),
  ]);

  return <Shell active="student" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <h1 className="heading">Student portal</h1>
    <p className="sub">{me.ok
      ? <>Signed in as <strong>{me.data.fullName}</strong> · student ID <strong>{me.data.studentNumber}</strong></>
      : "You’re signed in to Physics Cube Academy."}</p>
    <p className="link-row"><Link className="action" href="/student/attendance">My attendance →</Link></p>
    {!enrollments.ok || !classes.ok
      ? <p className="session-error" role="alert">We couldn’t load your classes right now. Please reload the page shortly.</p>
      : <>
        <section className="panel" aria-labelledby="my-classes-title">
          <h2 id="my-classes-title" className="panel-title">MY CLASSES</h2>
          <p className="panel-sub">{formatDate(window.from)} – {formatDate(window.lastDay)} · times in each class’s own time zone</p>
          <nav className="pager" aria-label="Four weeks">
            <Link className="text-link" href={href(window.previous)}>← Previous four weeks</Link>
            <Link className="text-link" href={href()}>From today</Link>
            <Link className="text-link" href={href(window.next)}>Next four weeks →</Link>
          </nav>
          <ul className="dir-list">
            {classes.data.map(c => <li key={c.id} className="dir-row dir-course" aria-label={c.title}>
              <div>
                <strong>{c.title}</strong>
                <span className="dir-dates muted"> {formatClassTime(c.startsAt, c.endsAt, c.timeZone)} ({c.timeZone})</span>
                <div className="dir-dates muted">{c.batches.map(batch => batch.code).join(", ")}</div>
              </div>
              {c.status === "SCHEDULED" && consent.ok && consent.data.enabled && consent.data.granted && classOpenForEngagement(c.startsAt, c.endsAt)
                ? <Link className="text-link" href={`/student/classes/${c.id}`}>Take part</Link> : null}
              <span className={`status ${c.status === "SCHEDULED" ? "on" : "off"}`}>{c.status === "SCHEDULED" ? "Scheduled" : "Cancelled"}</span>
            </li>)}
            {classes.data.length === 0 && <li className="dir-empty muted">No classes in these four weeks.</li>}
          </ul>
        </section>
        <section className="panel" aria-labelledby="my-batches-title">
          <h2 id="my-batches-title" className="panel-title">MY BATCHES</h2>
          <ul className="dir-list">
            {enrollments.data.map(e => <li key={e.id} className="dir-row dir-course">
              <div>
                <strong>{e.batchCode}</strong> {e.batchName}
                <div className="dir-dates muted">from {formatDate(e.enrolledOn)}{e.withdrawnOn ? `, withdrawn ${formatDate(e.withdrawnOn)}` : ""}</div>
              </div>
              <span className={`status ${e.withdrawnOn ? "off" : "on"}`}>{e.withdrawnOn ? "Withdrawn" : "Enrolled"}</span>
            </li>)}
            {enrollments.data.length === 0 && <li className="dir-empty muted">You aren’t enrolled in a batch yet. The academy office can enrol you.</li>}
          </ul>
        </section>
        {consent.ok && consent.data.enabled && <section className="panel" aria-labelledby="engagement-title">
          <h2 id="engagement-title" className="panel-title">REACTIONS AND QUESTIONS</h2>
          <p className="panel-sub">{consent.data.granted ? "You take part: during a class, open it below to react or answer questions."
            : consent.data.granted === false ? "You chose not to take part. Nothing is recorded about you." : "The academy asks whether you want to take part."}</p>
          <ConsentForm consent={consent.data} />
        </section>}
        <section className="panel" aria-labelledby="my-attendance-title">
          <h2 id="my-attendance-title" className="panel-title">MY ATTENDANCE</h2>
          <p className="panel-sub">Final attendance for your classes, decided by the academy’s rule from Zoom’s report. If something looks
            wrong, send an appeal and the academy will review it.</p>
          {!attendance.ok ? <p className="session-error" role="alert">We couldn’t load your attendance right now.</p>
            : <ul className="dir-list">
              {attendance.data.map((r, index) => <li key={r.id} className="dir-course my-attendance" aria-label={`Attendance ${r.classTitle}`}>
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
              {attendance.data.length === 0 && <li className="dir-empty muted">No final attendance yet.</li>}
            </ul>}
        </section>
      </>}
    <p className="link-row"><Link className="text-link" href="/account/password">Change password</Link></p>
    <form method="post" action="/api/session/logout">
      <button className="action secondary" type="submit">Sign out</button>
    </form>
  </Shell>;
}
