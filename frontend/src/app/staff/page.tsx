import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, classPhase, classTimeLabel, isAcademyManager, sessionWindow, type AttendanceAppeal, type ClassSession, type PendingAttendance } from "@/lib/staff-forms";
import { formatDuration, presets, renderTime, type Dashboard } from "@/lib/insights";
import { formatElapsed, formatTime, runStart, type WebinarMonitor } from "@/lib/webinars";

/**
 * What is happening and what needs doing, in that order: live webinars (from Zoom's actual runs, never the timetable),
 * then open tasks, today's classes, webinars that just finished, and a one-line week summary that leads to Reports.
 */
export default async function StaffPage() {
  const identity = await requirePortal("staff");
  const manager = isAcademyManager(identity.roles);
  const today = sessionWindow(null, undefined, 1);
  const teaches = manager || identity.roles.some(r => r === "INSTRUCTOR" || r === "ATTENDANCE_OFFICER");
  const week = presets(academyToday())[0];
  const [classes, pending, appeals, unmatched, failed, summary, monitors] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: today.fromInstant, to: today.toInstant })}`),
    manager ? staffApi<PendingAttendance>("/api/v1/attendance/pending") : Promise.resolve(null),
    manager ? staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN") : Promise.resolve(null),
    manager ? staffApi<unknown[]>("/api/v1/zoom/participants/unmatched?limit=200") : Promise.resolve(null),
    manager ? staffApi<unknown[]>("/api/v1/zoom/receipts?status=DEAD&limit=200") : Promise.resolve(null),
    teaches ? staffApi<Dashboard>(`/api/v1/attendance/dashboard?from=${week.from}&to=${week.to}`, undefined, 20000) : Promise.resolve(null),
    manager ? staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors") : Promise.resolve(null),
  ]);
  const now = renderTime();
  const todayLabel = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", weekday: "long", day: "numeric", month: "long", year: "numeric" })
    .format(new Date(now));
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
  const openTasks = tasks.filter(t => t.count > 0).length;
  // Live = a run Zoom actually started and has not ended (owners/administrators see webinars; others their live classes).
  const liveRuns = monitors?.ok ? monitors.data.filter(m => m.live).map(m => ({ m, run: m.live! })) : [];
  const liveClasses = !manager ? todays.filter(c => classPhase(c.startsAt, c.endsAt, now) === "live") : [];
  const finished = monitors?.ok ? monitors.data.flatMap(m => m.runs.filter(r => r.state === "ENDED" && r.actualEndedAt
    && now - Date.parse(r.actualEndedAt) < 36 * 3600_000).map(r => ({ m, r })))
    .sort((a, b) => Date.parse(b.r.actualEndedAt!) - Date.parse(a.r.actualEndedAt!)).slice(0, 5) : [];
  const t = summary?.ok ? summary.data.totals : null;

  return <StaffShell active="overview" crumb="Today" title="Staff portal"
    subtitle={todayLabel} hasStudentPortal={hasPortal(identity, "student")}
    actions={teaches ? <Link className="action secondary" href="/staff/dashboard">Attendance reports →</Link> : undefined}>
    {teaches && <section className={`panel live-panel ${liveRuns.length || liveClasses.length ? "is-live" : ""}`} aria-labelledby="live-title">
      <h2 id="live-title" className="panel-title">LIVE NOW</h2>
      {manager ? (liveRuns.length === 0
        ? <p className="panel-sub">No webinar is live right now. When Zoom starts a monitored webinar it appears here, from its actual start.</p>
        : <ul className="dir-list">{liveRuns.map(({ m, run }) => {
          const start = runStart(run);
          return <li key={run.occurrenceUuid} className="dir-row live-row" aria-label={`Live ${m.topic ?? m.webinarId}`}>
            <span><span className="pill pill-live">Live</span> <strong>{m.topic ?? "Zoom webinar"}</strong>
              <span className="muted"> · started {formatTime(start.at, m.timeZone)} · {formatElapsed((now - Date.parse(start.at)) / 1000)} so far
                · {run.connectedNow} connected now</span></span>
            <Link className="action" href={`/staff/sessions/${run.classSessionId}`}>Live attendance →</Link>
          </li>;
        })}</ul>)
        : liveClasses.length === 0 ? <p className="panel-sub">None of your classes is live right now.</p>
          : <ul className="dir-list">{liveClasses.map(c => <li key={c.id} className="dir-row live-row">
            <span><span className="pill pill-live">Live</span> <strong>{c.title}</strong> <span className="muted">{classTimeLabel(c, now)}</span></span>
            <Link className="action" href={`/staff/sessions/${c.id}`}>Live attendance →</Link></li>)}</ul>}
    </section>}

    <div className="staff-grid panel-gap">
      {manager ? <section className="panel" aria-labelledby="tasks-title">
        <h2 id="tasks-title" className="panel-title">NEEDS ATTENTION</h2>
        <p className="panel-sub">{openTasks === 0 ? "Nothing is waiting." : `${openTasks} kind${openTasks === 1 ? "" : "s"} of work waiting.`}</p>
        <ul className="dir-list">{tasks.map(task => <li key={task.label} className={`dir-row task-row ${task.count > 0 ? "has-work" : ""}`} aria-label={task.label}>
          <Link className="text-link" href={task.href}>{task.label}</Link>
          <strong className={task.count > 0 ? "attention" : "muted"}>{task.ok ? task.count : "—"}</strong>
        </li>)}</ul>
        {pending?.ok && pending.data.autoFinalise && <p className="panel-sub">Automatic finalisation is on.</p>}
      </section> : <section className="panel" aria-labelledby="setup-title">
        {identity.roles.includes("ANALYST") && !teaches ? <>
          <h2 id="setup-title" className="panel-title">REPORTS</h2>
          <p className="panel-sub">Attendance totals per batch, without student names.</p>
          <div className="nav-links"><Link className="action" href="/staff/attendance?section=totals">Attendance by batch →</Link></div>
        </> : <>
          <h2 id="setup-title" className="panel-title">YOUR BATCHES</h2>
          <p className="panel-sub">Your batches’ rosters, classes and attendance. Owners and administrators manage everything else.</p>
          <div className="nav-links">
            <Link className="action" href="/staff/courses">Courses &amp; batches →</Link>
            <Link className="action secondary" href="/staff/sessions">Classes →</Link>
          </div>
        </>}
      </section>}
      <section className="panel" aria-labelledby="today-title">
        <h2 id="today-title" className="panel-title">TODAY’S CLASSES</h2>
        {!classes.ok ? <p className="panel-sub">{classes.status === 403 ? "Your role has no classes." : "We couldn’t load today’s classes."}</p> : <>
          <ul className="dir-list">{todays.map(c => {
            const phase = classPhase(c.startsAt, c.endsAt, now);
            return <li key={c.id} className="dir-row" aria-label={`Today ${c.title}`}>
              <span><Link className="text-link" href={`/staff/sessions/${c.id}`}><strong>{c.title}</strong></Link>
                {" "}<span className="muted">{classTimeLabel(c, now)}</span></span>
              <span className={`status ${phase === "live" ? "on" : "off"}`}>{phase === "live" ? "Live now" : phase === "over" ? "Ended"
                : c.zoomWebinarId ? "Upcoming" : "No webinar ID"}</span>
            </li>;
          })}
            {todays.length === 0 && <li className="dir-empty muted">No classes today.</li>}</ul>
          {manager && <p className="link-row"><Link className="text-link" href="/staff/webinars">Monitor a webinar →</Link></p>}
        </>}
      </section>
    </div>

    {manager && finished.length > 0 && <section className="panel panel-gap" aria-labelledby="finished-title">
      <h2 id="finished-title" className="panel-title">RECENTLY FINISHED</h2>
      <ul className="dir-list">{finished.map(({ m, r }) => <li key={r.occurrenceUuid} className="dir-row">
        <span><strong>{m.topic ?? "Zoom webinar"}</strong> <span className="muted">ended {formatTime(r.actualEndedAt!, m.timeZone)}
          · {r.people} {r.people === 1 ? "person" : "people"}</span></span>
        <span className="dir-meta"><span className={`state ${r.reported ? "state-report" : ""}`}>{r.reported ? "Zoom report in" : "Waiting for Zoom’s report"}</span>
          <Link className="text-link" href={`/staff/sessions/${r.classSessionId}`}>Attendance →</Link></span>
      </li>)}</ul>
    </section>}

    {t && <p className="week-line panel-gap">Last 7 days: <strong>{t.classes}</strong> class{t.classes === 1 ? "" : "es"} held
      {t.classes ? <> ({formatDuration(t.classSeconds)})</> : null} · <strong>{t.students}</strong> students expected
      · average attendance <strong>{t.averagePercent}%</strong>{t.minPercent ? ` (rule ${t.minPercent}%)` : ""}
      {" "}· <Link className="text-link" href="/staff/dashboard">Reports →</Link></p>}
  </StaffShell>;
}
