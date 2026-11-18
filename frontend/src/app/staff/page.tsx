import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { classPhase, formatClassTime, isAcademyManager, sessionWindow, type AttendanceAppeal, type ClassSession, type PendingAttendance } from "@/lib/staff-forms";

/** One glance at what needs attention today; every number links to where it is handled. */
export default async function StaffPage() {
  const identity = await requirePortal("staff");
  const manager = isAcademyManager(identity.roles);
  const today = sessionWindow(null, undefined, 1);
  const [classes, pending, appeals, unmatched, failed] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: today.fromInstant, to: today.toInstant })}`),
    manager ? staffApi<PendingAttendance>("/api/v1/attendance/pending") : Promise.resolve(null),
    manager ? staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN") : Promise.resolve(null),
    manager ? staffApi<unknown[]>("/api/v1/zoom/participants/unmatched?limit=200") : Promise.resolve(null),
    manager ? staffApi<unknown[]>("/api/v1/zoom/receipts?status=DEAD&limit=200") : Promise.resolve(null),
  ]);
  const todays = classes.ok ? classes.data.filter(c => c.status === "SCHEDULED") : [];
  const ready = pending?.ok ? pending.data.classes.filter(c => c.state === "READY").length : 0;
  const waiting = pending?.ok ? pending.data.classes.length - ready : 0;
  const tasks = manager ? [
    { label: "Classes ready to finalise", count: ready, href: "/staff/attendance", ok: !!pending?.ok },
    { label: "Ended classes waiting for Zoom", count: waiting, href: "/staff/attendance", ok: !!pending?.ok },
    { label: "Appeals waiting for a decision", count: appeals?.ok ? appeals.data.length : 0, href: "/staff/attendance", ok: !!appeals?.ok },
    { label: "Zoom accounts to match", count: unmatched?.ok ? unmatched.data.length : 0, href: "/staff/zoom", ok: !!unmatched?.ok },
    { label: "Failed Zoom events", count: failed?.ok ? failed.data.length : 0, href: "/staff/zoom", ok: !!failed?.ok },
  ] : [];
  return <StaffShell active="overview" crumb="Operations" title="Staff portal"
    subtitle="You’re signed in to Physics Cube Academy." hasStudentPortal={hasPortal(identity, "student")}>
    {!identity.roles.every(r => r === "ANALYST") && <p className="link-row">
      <Link className="action" href="/staff/dashboard">Open the attendance dashboard →</Link></p>}
    <div className="staff-grid">
      <section className="panel" aria-labelledby="today-title">
        <h2 id="today-title" className="panel-title">TODAY’S CLASSES</h2>
        {!classes.ok ? <p className="panel-sub">{classes.status === 403 ? "Your role has no classes." : "We couldn’t load today’s classes."}</p> : <>
          <ul className="dir-list">{todays.map(c => {
            const phase = classPhase(c.startsAt, c.endsAt);
            const live = phase === "live";
            const over = phase === "over";
            return <li key={c.id} className="dir-row" aria-label={`Today ${c.title}`}>
              <span><Link className="text-link" href={`/staff/sessions/${c.id}`}><strong>{c.title}</strong></Link>
                {" "}<span className="muted">{formatClassTime(c.startsAt, c.endsAt, c.timeZone)}</span></span>
              <span className={`status ${live ? "on" : "off"}`}>{live ? "Live now" : over ? "Ended" : c.zoomWebinarId ? "Upcoming" : "No webinar ID"}</span>
            </li>;
          })}
            {todays.length === 0 && <li className="dir-empty muted">No classes today.</li>}</ul>
          {manager && <p className="link-row"><Link className="text-link" href="/staff/zoom/import">Import classes from Zoom →</Link></p>}
        </>}
      </section>
      {manager ? <section className="panel" aria-labelledby="tasks-title">
        <h2 id="tasks-title" className="panel-title">NEEDS ATTENTION</h2>
        <ul className="dir-list">{tasks.map(t => <li key={t.label} className="dir-row" aria-label={t.label}>
          <Link className="text-link" href={t.href}>{t.label}</Link>
          <strong className={t.count > 0 ? "attention" : "muted"}>{t.ok ? t.count : "—"}</strong>
        </li>)}</ul>
        {pending?.ok && pending.data.autoFinalise && <p className="panel-sub">Automatic finalisation is on.</p>}
      </section> : <section className="panel" aria-labelledby="setup-title">
        {identity.roles.includes("ANALYST") && !identity.roles.some(r => r === "INSTRUCTOR" || r === "ATTENDANCE_OFFICER") ? <>
          <h2 id="setup-title" className="panel-title">REPORTS</h2>
          <p className="panel-sub">Attendance totals per batch, without student names.</p>
          <div className="nav-links"><Link className="action" href="/staff/attendance">Attendance by batch →</Link></div>
        </> : <>
          <h2 id="setup-title" className="panel-title">YOUR BATCHES</h2>
          <p className="panel-sub">Your batches’ rosters, classes and attendance. Owners and administrators manage everything else.</p>
          <div className="nav-links">
            <Link className="action" href="/staff/courses">Courses &amp; batches →</Link>
            <Link className="action secondary" href="/staff/sessions">Classes →</Link>
          </div>
        </>}
      </section>}
    </div>
  </StaffShell>;
}
