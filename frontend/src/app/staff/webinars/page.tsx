import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { LiveRefresh } from "@/components/LiveRefresh";
import { AddWebinarForm, WebinarActions } from "@/components/staff/WebinarForms";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { isAcademyManager } from "@/lib/staff-forms";
import {
  formatElapsed, formatTime, formatWebinarId, formatWhen, runStart, syncNote, upcomingOccurrences, zoomSchedule,
  type WebinarMonitor, type WebinarRun,
} from "@/lib/webinars";

export const metadata = { title: "Webinars · LUMA" };

function RunRow({ run, timeZone, now }: { run: WebinarRun; timeZone: string | null; now: number }) {
  const start = runStart(run);
  const end = run.actualEndedAt;
  const seconds = ((end ? Date.parse(end) : now) - Date.parse(start.at)) / 1000;
  return <li className="dir-row run-row" aria-label={`Run ${formatWhen(start.at, timeZone)}`}>
    <span>
      <strong>{formatWhen(start.at, timeZone)}</strong>
      <span className="muted"> · {start.known ? "started" : "first seen (Zoom’s start not received)"} {formatTime(start.at, timeZone)}
        {end ? `, ended ${formatTime(end, timeZone)}` : ""} · {formatElapsed(seconds)} · {run.people} {run.people === 1 ? "person" : "people"}</span>
    </span>
    <span className="dir-meta">
      {run.state === "LIVE" ? <span className="pill pill-live">Live</span>
        : <span className={`state ${run.reported ? "state-report" : ""}`}>{run.reported ? "Zoom report in" : "Waiting for Zoom’s report"}</span>}
      <Link className="text-link" href={`/staff/sessions/${run.classSessionId}`}>Attendance →</Link>
    </span>
  </li>;
}

export default async function WebinarsPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "webinars" as const, crumb: "Teaching", title: "Webinars",
    subtitle: "Zoom owns the schedule. Add a webinar by its ID or link; every time Zoom actually starts it, LUMA records attendance from that moment.",
    hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}>
      <p className="banner">Only academy owners and administrators choose which webinars LUMA monitors. Your classes and their attendance are under
        {" "}<Link className="text-link" href="/staff/sessions">Classes</Link>.</p>
    </StaffShell>;
  }
  const [monitors, academy] = await Promise.all([
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
    staffApi<{ className: string }[]>("/api/v1/academy/classes", undefined, 20000),
  ]);
  const now = renderTime();
  const classOptions = academy.ok ? academy.data.map(c => c.className) : null;
  if (!monitors.ok) {
    return <StaffShell {...shell}>
      <p className="session-error" role="alert">We couldn’t load the monitored webinars right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const live = monitors.data.filter(m => m.live);
  const liveUntil = new Date(now + 12 * 3600_000).toISOString();

  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="add-title">
      <h2 id="add-title" className="panel-title">MONITOR A WEBINAR</h2>
      <AddWebinarForm />
    </section>

    <section className="panel panel-gap" aria-labelledby="live-title">
      <h2 id="live-title" className="panel-title">LIVE NOW</h2>
      {live.length === 0 ? <p className="panel-sub">No monitored webinar is running. When Zoom starts one, it appears here with its attendance,
        even if nobody had LUMA open.</p>
        : <>
          <LiveRefresh until={liveUntil} seconds={30} />
          <ul className="live-cards">{live.map(m => {
            const run = m.live!;
            const start = runStart(run);
            return <li key={m.id} className="live-card" aria-label={`Live ${m.topic ?? m.webinarId}`}>
              <p className="live-card-label">● LIVE · {formatWebinarId(m.webinarId)}</p>
              <p className="live-card-title">{m.topic ?? "Zoom webinar"}</p>
              <dl className="live-facts">
                <div><dt>Actual start</dt><dd>{formatTime(start.at, m.timeZone)}{start.known ? "" : " (first event)"}</dd></div>
                <div><dt>Zoom schedule</dt><dd>{m.scheduledStartAt ? formatTime(m.scheduledStartAt, m.timeZone) : "none"}</dd></div>
                <div><dt>Running for</dt><dd>{formatElapsed((now - Date.parse(start.at)) / 1000)}</dd></div>
                <div><dt>Connected now</dt><dd>{run.connectedNow}</dd></div>
                <div><dt>Joined so far</dt><dd>{run.people}</dd></div>
              </dl>
              <Link className="action" href={`/staff/sessions/${run.classSessionId}`}>Live attendance →</Link>
            </li>;
          })}</ul>
        </>}
    </section>

    <section className="panel panel-gap" aria-labelledby="timetable-title">
      <h2 id="timetable-title" className="panel-title">ZOOM TIMETABLE</h2>
      <p className="panel-sub">Read-only, from Zoom. Change a webinar’s title or time in Zoom; LUMA shows the change after it next reads Zoom
        (every 30 minutes, or Refresh from Zoom).</p>
      {monitors.data.length === 0 && <p className="muted">No webinars monitored yet. Add one above.</p>}
      <ul className="webinar-cards">{monitors.data.map(m => {
        const sync = syncNote(m, now);
        const upcoming = upcomingOccurrences(m.occurrences, now);
        const title = m.topic ?? `Webinar ${formatWebinarId(m.webinarId)}`;
        return <li key={m.id} className={`webinar-card ${m.active ? "" : "is-stopped"}`} aria-label={`Webinar ${formatWebinarId(m.webinarId)}`}>
          <div className="webinar-head">
            <div>
              <p className="webinar-title">{title}</p>
              <p className="muted">ID {formatWebinarId(m.webinarId)} · {zoomSchedule(m)}</p>
            </div>
            <span className="dir-meta">
              {m.live ? <span className="pill pill-live">Live</span> : <span className="state">{m.active ? "Not live" : "Not monitored"}</span>}
            </span>
          </div>
          {upcoming.length > 0 && <div className="occurrences">
            <p className="occurrences-label">Next in Zoom</p>
            <ul>{upcoming.map(o => <li key={o.occurrenceId ?? o.startTime}>{formatWhen(o.startTime!, m.timeZone)}
              {o.durationMinutes ? ` · ${o.durationMinutes} min` : ""}</li>)}</ul>
          </div>}
          <p className={`sync-note ${sync.stale ? "is-stale" : ""}`}>{sync.text}</p>
          <p className={`class-note ${m.classResolved ? "" : "is-unresolved"}`}>
            {m.classResolved
              ? <>Academy class: <strong>{m.academyClassName}</strong> ({m.classSource === "ADMIN" ? "chosen by staff" : "matches the Zoom title"})
                {m.classBatchId ? <> · <Link className="text-link" href={`/staff/batches/${m.classBatchId}`}>students</Link></>
                  : <> · not set up in LUMA yet: <Link className="text-link" href="/staff/academy">set it up</Link> so its students are expected</>}</>
              : <>Academy class unresolved: the Zoom title matches no single Academy class. Choose it below; until then attendance is recorded
                but nobody is expected or marked absent.</>}
          </p>
          <WebinarActions monitorId={m.id} topic={title} active={m.active} academyClassName={m.academyClassName} classOptions={classOptions} />
          <details className="runs">
            <summary>Runs ({m.runs.length})</summary>
            {m.runs.length === 0 ? <p className="muted">No run yet. Each time Zoom starts this webinar, a run appears here.</p>
              : <ul className="dir-list">{m.runs.map(run => <RunRow key={run.occurrenceUuid} run={run} timeZone={m.timeZone} now={now} />)}</ul>}
          </details>
        </li>;
      })}</ul>
      {!classOptions && monitors.data.length > 0 && <p className="panel-sub">The Academy database is not connected, so Academy classes can’t be
        chosen here yet.</p>}
    </section>
  </StaffShell>;
}
