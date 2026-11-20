import Link from "next/link";
import { Suspense } from "react";
import type { CSSProperties } from "react";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { AddWebinarForm, WebinarActions } from "@/components/staff/WebinarForms";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, addDays, formatDate, sessionListHref, sessionWindow, zonedToInstant, type ClassSession } from "@/lib/staff-forms";
import { attendancePercent, averageClassAttendance, calendarEntries, compactClassTitle, longDayLabel, monthLabel, monthlyClassTypes,
  timeRange, type ClassAttendanceTotal, type DayEntry } from "@/lib/staff-home";
import { formatWebinarId, zoomSchedule, type WebinarMonitor } from "@/lib/webinars";

export const metadata = { title: "Classes · LUMA" };

const stateLabels: Record<DayEntry["state"], string> = { live: "Live", now: "In progress", upcoming: "Expected", ended: "Ended" };

function classTotals(from: string, to: string) {
  return staffApi<ClassAttendanceTotal[]>(`/api/v1/attendance/class-totals?${new URLSearchParams({ from, to })}`, undefined, 25000);
}

type Bar = { id: string; label: string; title: string; percent: number };

function AttendanceChart({ title, bars, failed }: { title: string; bars: Bar[]; failed: boolean }) {
  return <figure className="panel classes-attendance-chart">
    <figcaption className="panel-title">{title}</figcaption>
    {bars.length === 0 ? <p className="panel-sub">{failed ? "Attendance couldn’t load." : "No attendance percentages in this period yet."}</p>
      : <div className="classes-chart-frame">
        <div className="classes-chart-axis" aria-hidden="true"><span>100%</span><span>50%</span><span>0%</span></div>
        <ul className="classes-chart-bars" aria-label={title}>
          {bars.map(({ id, label, title, percent }) => <li key={id} tabIndex={0} title={`${title} · ${percent}%`}
            aria-label={`${title}, ${percent}%`}>
            <div className="classes-chart-track" aria-hidden="true">
              <div className="classes-chart-fill" style={{ "--attendance": `${percent}%` } as CSSProperties} />
              <span className="classes-chart-value">{percent}%</span>
            </div>
            <span className="classes-chart-label" aria-hidden="true">{label}</span>
          </li>)}
        </ul>
      </div>}
  </figure>;
}

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
  const now = renderTime();
  const today = academyToday(new Date(now));
  const window = sessionWindow(params.from, today);
  const month = today.slice(0, 7);
  const shell = { active: "sessions" as const, crumb: "Teaching", title: "Classes",
    subtitle: "Classes Zoom has scheduled for the webinars you track, and the ones already held. Times come from Zoom.",
    hasStudentPortal: hasPortal(identity, "student") };

  const query = new URLSearchParams({ from: window.fromInstant, to: window.toInstant });
  const [sessions, monitors, totals] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
    window.from <= today ? classTotals(window.from, window.lastDay < today ? window.lastDay : today) : null,
  ]);
  const monitorList = monitors.ok ? monitors.data : [];
  const liveIds = new Set(monitorList.flatMap(m => m.live ? [m.live.classSessionId] : []));
  const byDay = sessions.ok ? calendarEntries(sessions.data, monitorList, liveIds, now) : new Map<string, DayEntry[]>();
  const days: string[] = [];
  for (let day = window.from; day < window.to; day = addDays(day, 1)) if (byDay.has(day)) days.push(day);
  const count = days.reduce((n, day) => n + (byDay.get(day)?.length ?? 0), 0);
  const byId = new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : []);
  const bars = days.flatMap(day => (byDay.get(day) ?? []).flatMap(entry => {
    const total = entry.sessionId && entry.state === "ended" ? byId.get(entry.sessionId) : undefined;
    const percent = total ? attendancePercent(total) : null;
    return percent == null ? [] : [{ id: entry.key, label: day.slice(-2), title: `${compactClassTitle(entry.title)} · ${formatDate(day)}`, percent }];
  }));

  return <StaffShell {...shell}>
    <div className="classes-overview">
      <div className="classes-column">
        <AttendanceChart title="ATTENDANCE BY CLASS" bars={bars} failed={!sessions.ok || totals?.ok === false} />
        <section className="panel" aria-labelledby="classes-title">
          <h2 id="classes-title" className="panel-title">CLASSES</h2>
          <p className="panel-sub">{formatDate(window.from)} – {formatDate(window.lastDay)} · Sri Lanka time · {count} class{count === 1 ? "" : "es"}</p>
          <nav className="pager" aria-label="Fortnight">
            <Link className="text-link" href={sessionListHref({ from: window.previous })}>← Previous two weeks</Link>
            <Link className="text-link" href={sessionListHref({})}>From today</Link>
            <Link className="text-link" href={sessionListHref({ from: window.next })}>Next two weeks →</Link>
          </nav>
          <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Two-week schedule">
            {!sessions.ok && <p className="session-error" role="alert">We couldn’t load classes right now. Please reload the page shortly.</p>}
            {sessions.ok && days.length === 0 && <p className="dir-empty muted">No classes in these two weeks.
              {monitorList.length === 0 ? " Track a Zoom link below to see its classes here." : ""}</p>}
            {days.map(day => <div key={day} className="class-day" role="group" aria-label={longDayLabel(day)}>
              <h3 className="class-day-title">{longDayLabel(day)}</h3>
              <ul className="home-day-list">{byDay.get(day)!.map(entry => {
                const total = entry.sessionId ? byId.get(entry.sessionId) : undefined;
                const percent = total ? attendancePercent(total) : null;
                const body = <>
                  <span className="home-entry-time">{timeRange(entry)}</span>
                  <span className="home-entry-copy">
                    <OneLineTitle text={entry.title} />
                    <span className="home-entry-meta">{entryNote(entry)}{entry.state === "ended" && entry.sessionId && <>
                      {" · "}<strong className="class-attendance-percent">{percent == null ? "—" : `${percent}%`}</strong>
                    </>}</span>
                  </span>
                  <span className={`home-state home-state-${entry.state}`}>{stateLabels[entry.state]}</span>
                </>;
                return <li key={entry.key} className={`home-entry home-entry-${entry.state}`} aria-label={`${entry.title}, ${stateLabels[entry.state]}`}>
                  {entry.sessionId ? <Link href={entry.href}>{body}</Link> : <div className="home-entry-static">{body}</div>}
                </li>;
              })}</ul>
          </div>)}
          </div>
        </section>
      </div>
      <Suspense fallback={<section className="panel"><p className="panel-sub">Loading class types…</p></section>}>
        <ClassTypes month={month} today={today} now={now} />
      </Suspense>
    </div>
    <section className="panel panel-gap" aria-labelledby="track-title">
      <h2 id="track-title" className="panel-title">TRACK A ZOOM LINK</h2>
      <AddWebinarForm />
      {monitors.ok && monitorList.length > 0 && <Suspense fallback={<p className="panel-sub">Loading tracked links…</p>}>
        <TrackedLinks monitors={monitorList} />
      </Suspense>}
      {!monitors.ok && <p className="session-error" role="alert">Tracked Zoom links couldn’t load. Please reload to see Zoom’s scheduled classes.</p>}
    </section>
  </StaffShell>;
}

async function ClassTypes({ month, today, now }: { month: string; today: string; now: number }) {
  const query = new URLSearchParams({ from: zonedToInstant(`${month}-01`, "00:00", ACADEMY_TIME_ZONE),
    to: zonedToInstant(addDays(today, 1), "00:00", ACADEMY_TIME_ZONE) });
  const [result, totals] = await Promise.all([staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`), classTotals(`${month}-01`, today)]);
  const byId = new Map(totals.ok ? totals.data.map(total => [total.id, total]) : []);
  const groups = result.ok ? monthlyClassTypes(result.data, now).map(([key, group]) => ({ ...group, key,
    average: averageClassAttendance(group.sessions, byId),
  })) : [];

  return <div className="classes-column">
    <AttendanceChart title="AVERAGE ATTENDANCE BY CLASS TYPE" failed={!result.ok || !totals.ok}
      bars={groups.flatMap(group => group.average == null ? [] : [{ id: group.key, label: group.title.split("|")[0].trim(),
        title: group.title, percent: group.average }])} />
    <section className="panel" aria-labelledby="class-types-title">
      <h2 className="panel-title" id="class-types-title">CLASS TYPES THIS MONTH</h2>
      <p className="panel-sub">{monthLabel(month)} · through {formatDate(today)}</p>
      <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly class types and dates">
        {!result.ok ? <p className="session-error" role="alert">Class types couldn’t load. Please reload shortly.</p>
          : groups.length === 0 ? <p className="dir-empty muted">No completed class entries this month.</p>
          : <ul className="class-type-list">{groups.map(group => <li className="class-type" key={group.key}>
            <Link href={`/staff/sessions/types?${new URLSearchParams({ type: group.key })}`}>
              <span>{group.title}</span>
              <span className="class-type-count">{group.sessions.length} {group.sessions.length === 1 ? "session" : "sessions"}
                {" · "}<strong className="class-attendance-percent">{group.average == null ? "—" : `${group.average}% avg`}</strong></span>
            </Link>
          </li>)}</ul>}
      </div>
    </section>
  </div>;
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
