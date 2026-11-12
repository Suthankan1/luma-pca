import Link from "next/link";
import { Shell } from "@/components/Shell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { formatClassTime, formatDate, sessionWindow } from "@/lib/staff-forms";

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
  const [enrollments, classes] = await Promise.all([
    studentApi<MyEnrollment[]>("/api/v1/me/enrollments"),
    studentApi<MyClass[]>(`/api/v1/me/class-sessions?${query}`),
  ]);

  return <Shell active="student" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <h1 className="heading">Student portal</h1>
    <p className="sub">You’re signed in to Physics Cube Academy.</p>
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
        <p className="banner">Attendance history will appear here when available.</p>
      </>}
    <form method="post" action="/api/session/logout">
      <button className="action secondary" type="submit">Sign out</button>
    </form>
  </Shell>;
}
