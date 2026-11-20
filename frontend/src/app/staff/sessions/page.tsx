import Link from "next/link";
import { Suspense } from "react";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { AddWebinarForm, WebinarActions } from "@/components/staff/WebinarForms";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { addDays, formatDate, sessionListHref, sessionWindow, type ClassSession } from "@/lib/staff-forms";
import { calendarEntries, longDayLabel, timeRange, type DayEntry } from "@/lib/staff-home";
import { formatWebinarId, zoomSchedule, type WebinarMonitor } from "@/lib/webinars";

export const metadata = { title: "Classes · LUMA" };

const stateLabels: Record<DayEntry["state"], string> = { live: "Live", now: "In progress", upcoming: "Expected", ended: "Ended" };

/** What a row says under its title: where it comes from and what to do with it. */
function entryNote(entry: DayEntry) {
  if (entry.source === "zoom") return entry.state === "ended" ? "Scheduled in Zoom; no run was recorded" : "Scheduled in Zoom";
  if (entry.state === "upcoming") return "Scheduled";
  return entry.state === "ended" ? "Attendance →" : "Live attendance →";
}

/**
 * One screen for classes: what Zoom has scheduled for the tracked webinars, and the classes that ran (each Zoom run
 * becomes a class with attendance from its actual start). Tracking a webinar takes only its ID or join link.
 */
export default async function SessionsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const window = sessionWindow(params.from);
  const shell = { active: "sessions" as const, crumb: "Teaching", title: "Classes",
    subtitle: "Classes Zoom has scheduled for the webinars you track, and the ones already held. Times come from Zoom.",
    hasStudentPortal: hasPortal(identity, "student") };

  const query = new URLSearchParams({ from: window.fromInstant, to: window.toInstant });
  const [sessions, monitors] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
  ]);
  const now = renderTime();
  const monitorList = monitors.ok ? monitors.data : [];
  const liveIds = new Set(monitorList.flatMap(m => m.live ? [m.live.classSessionId] : []));
  const byDay = sessions.ok ? calendarEntries(sessions.data, monitorList, liveIds, now) : new Map<string, DayEntry[]>();
  const days: string[] = [];
  for (let day = window.from; day < window.to; day = addDays(day, 1)) if (byDay.has(day)) days.push(day);
  const count = days.reduce((n, day) => n + (byDay.get(day)?.length ?? 0), 0);

  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="track-title">
      <h2 id="track-title" className="panel-title">TRACK A ZOOM LINK</h2>
      <AddWebinarForm />
      {monitors.ok && monitorList.length > 0 && <Suspense fallback={<p className="panel-sub">Loading tracked links…</p>}>
        <TrackedLinks monitors={monitorList} />
      </Suspense>}
      {!monitors.ok && <p className="session-error" role="alert">Tracked Zoom links couldn’t load, so Zoom’s scheduled classes are
        missing below. Please reload shortly.</p>}
    </section>

    <section className="panel panel-gap" aria-labelledby="classes-title">
      <h2 id="classes-title" className="panel-title">CLASSES</h2>
      <p className="panel-sub">{formatDate(window.from)} – {formatDate(window.lastDay)} · Sri Lanka time · {count} class{count === 1 ? "" : "es"}</p>
      <nav className="pager" aria-label="Fortnight">
        <Link className="text-link" href={sessionListHref({ from: window.previous })}>← Previous two weeks</Link>
        <Link className="text-link" href={sessionListHref({})}>From today</Link>
        <Link className="text-link" href={sessionListHref({ from: window.next })}>Next two weeks →</Link>
      </nav>
      {!sessions.ok && <p className="session-error" role="alert">We couldn’t load classes right now. Please reload the page shortly.</p>}
      {sessions.ok && days.length === 0 && <p className="dir-empty muted">No classes in these two weeks.
        {monitorList.length === 0 ? " Track a Zoom link above to see its classes here." : ""}</p>}
      {days.map(day => <div key={day} className="class-day" role="group" aria-label={longDayLabel(day)}>
        <h3 className="class-day-title">{longDayLabel(day)}</h3>
        <ul className="home-day-list">{byDay.get(day)!.map(entry => {
          const body = <>
            <span className="home-entry-time">{timeRange(entry)}</span>
            <span className="home-entry-copy">
              <OneLineTitle text={entry.title} />
              <span className="home-entry-meta">{entryNote(entry)}</span>
            </span>
            <span className={`home-state home-state-${entry.state}`}>{stateLabels[entry.state]}</span>
          </>;
          return <li key={entry.key} className={`home-entry home-entry-${entry.state}`} aria-label={`${entry.title}, ${stateLabels[entry.state]}`}>
            {entry.sessionId ? <Link href={entry.href}>{body}</Link> : <div className="home-entry-static">{body}</div>}
          </li>;
        })}</ul>
      </div>)}
    </section>
  </StaffShell>;
}

/** The tracked webinars, collapsed: refresh from Zoom, stop or resume tracking, and the Academy class whose students are expected. */
async function TrackedLinks({ monitors }: { monitors: WebinarMonitor[] }) {
  const academy = await staffApi<{ className: string }[]>("/api/v1/academy/classes", undefined, 20000);
  const classOptions = academy.ok ? academy.data.map(c => c.className) : null;
  const active = monitors.filter(m => m.active).length;
  return <Disclosure summary="Tracked Zoom links" hint={`${active} tracked${monitors.length > active ? `, ${monitors.length - active} stopped` : ""}`}>
    <ul className="dir-list">{monitors.map(m => {
      const title = m.topic ?? `Webinar ${formatWebinarId(m.webinarId)}`;
      return <li key={m.id} className="dir-course tracked-link" aria-label={`Tracked ${formatWebinarId(m.webinarId)}`}>
        <div className="dir-row">
          <span><strong>{title}</strong> <span className="muted">· ID {formatWebinarId(m.webinarId)} · {zoomSchedule(m)}</span></span>
          <span className={`state ${m.active ? (m.classResolved ? "state-final" : "state-warn") : ""}`}>{!m.active ? "Stopped"
            : m.classResolved ? (m.academyClassName ?? "Class set") : "Academy class not set"}</span>
        </div>
        <WebinarActions monitorId={m.id} topic={title} active={m.active} academyClassName={m.academyClassName} classOptions={classOptions} />
      </li>;
    })}</ul>
    {!classOptions && <p className="panel-sub">The Academy database is not connected, so the Academy class can’t be chosen here yet.</p>}
  </Disclosure>;
}
