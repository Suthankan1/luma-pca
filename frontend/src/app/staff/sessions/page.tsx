import Link from "next/link";
import { Suspense } from "react";
import type { CSSProperties } from "react";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { ClassScheduleScroll } from "@/components/staff/ClassScheduleScroll";
import { AddWebinarForm, WebinarActions } from "@/components/staff/WebinarForms";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, formatDate, instantParts, type ClassSession } from "@/lib/staff-forms";
import { attendancePercent, averageClassAttendance, calendarEntries, classMonthRange, compactClassTitle, longDayLabel, monthLabel, monthlyClassTypes,
  shiftMonth, timeRange, type ClassAttendanceTotal, type DayEntry } from "@/lib/staff-home";
import { formatWebinarId, zoomSchedule, type WebinarMonitor } from "@/lib/webinars";

export const metadata = { title: "Classes · LUMA" };

const stateLabels: Record<DayEntry["state"], string> = { live: "Live", now: "In progress", upcoming: "Expected", ended: "Ended" };

function classTotals(from: string, to: string) {
  return staffApi<ClassAttendanceTotal[]>(`/api/v1/attendance/class-totals?${new URLSearchParams({ from, to })}`, undefined, 25000);
}

type Bar = { id: string; label: string; title: string; percent: number | null };

function AttendanceChart({ title, bars, failed }: { title: string; bars: Bar[]; failed: boolean }) {
  return <figure className="panel classes-attendance-chart">
    <figcaption className="panel-title">{title}</figcaption>
    {bars.length === 0 ? <p className="panel-sub">{failed ? "Attendance couldn’t load." : "No attendance percentages in this period yet."}</p>
      : <div className="classes-chart-frame">
        <div className="classes-chart-axis" aria-hidden="true"><span>100%</span><span>50%</span><span>0%</span></div>
        <ul className="classes-chart-bars" aria-label={title}>
          {bars.map(({ id, label, title, percent }) => <li key={id} tabIndex={0}
            title={`${title} · ${percent == null ? "Attendance unavailable" : `${percent}%`}`}
            aria-label={`${title}, ${percent == null ? "attendance unavailable" : `${percent}%`}`}>
            <div className="classes-chart-track" aria-hidden="true">
              {percent != null && <div className="classes-chart-fill" style={{ "--attendance": `${percent}%` } as CSSProperties} />}
              <span className="classes-chart-value">{percent == null ? "—" : `${percent}%`}</span>
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
  const { month, first, last, through, fromInstant, toInstant } = classMonthRange(params.month, today, params.from);
  const currentMonth = today.startsWith(month);
  const shell = { active: "sessions" as const, crumb: "Teaching", title: "Classes",
    subtitle: "Classes Zoom has scheduled for the webinars you track, and the ones already held. Times come from Zoom.",
    hasStudentPortal: hasPortal(identity, "student") };

  const query = new URLSearchParams({ from: fromInstant, to: toInstant });
  const [sessions, monitors, totals] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`, undefined, 25000),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
    through ? classTotals(first, through) : null,
  ]);
  const monitorList = monitors.ok ? monitors.data : [];
  const liveIds = new Set(monitorList.flatMap(m => m.live ? [m.live.classSessionId] : []));
  const monthSessions = sessions.ok ? sessions.data.map(session => ({
    ...session, localDate: instantParts(session.startsAt, ACADEMY_TIME_ZONE).date,
  })) : [];
  const byDay = calendarEntries(monthSessions, sessions.ok ? monitorList : null, liveIds, now);
  const days = [...byDay.keys()].filter(day => day >= first && day <= last).sort();
  const scheduleDays = currentMonth && !byDay.has(today) && sessions.ok ? [...days, today].sort() : days;
  const count = days.reduce((n, day) => n + (byDay.get(day)?.length ?? 0), 0);
  const byId = new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : []);
  const bars = days.flatMap(day => (byDay.get(day) ?? []).flatMap(entry => {
    if (!entry.sessionId || entry.state !== "ended" || day > today) return [];
    const total = byId.get(entry.sessionId);
    return [{ id: entry.key, label: day.slice(-2), title: `${compactClassTitle(entry.title)} · ${formatDate(day)}`,
      percent: total ? attendancePercent(total) : null }];
  }));

  return <StaffShell {...shell}>
    <nav className="classes-month-picker" aria-label="Browse class months">
      <Link className="home-step" href={`/staff/sessions?month=${shiftMonth(month, -1)}`} aria-label="Previous month">‹</Link>
      <form action="/staff/sessions">
        <input aria-label="Class month" type="month" name="month" key={month} defaultValue={month}
          pattern="[0-9]{4}-(0[1-9]|1[0-2])" required />
        <button className="home-step" type="submit">View</button>
      </form>
      <Link className="home-step" href={`/staff/sessions?month=${shiftMonth(month, 1)}`} aria-label="Next month">›</Link>
      {!currentMonth && <Link className="text-link" href="/staff/sessions">This month</Link>}
    </nav>
    <div className="classes-overview">
      <div className="classes-column">
        <AttendanceChart title="ATTENDANCE BY CLASS" bars={bars} failed={!sessions.ok || totals?.ok === false} />
        <section className="panel" aria-labelledby="classes-title">
          <h2 id="classes-title" className="panel-title">CLASSES</h2>
          <p className="panel-sub"><strong>{monthLabel(month)}</strong> · {formatDate(first)} – {formatDate(last)} · Sri Lanka time · {count} class{count === 1 ? "" : "es"}</p>
          <ClassScheduleScroll key={month} anchorDay={currentMonth ? today : first}>
            {!sessions.ok && <p className="session-error" role="alert">We couldn’t load classes right now. Please reload the page shortly.</p>}
            {sessions.ok && days.length === 0 && <p className="dir-empty muted">No classes scheduled or held this month.
              {monitorList.length === 0 ? " Track a Zoom link below to see its classes here." : ""}</p>}
            {scheduleDays.map(day => <div key={day} data-schedule-day={day} className="class-day" role="group" aria-label={longDayLabel(day)}>
              <h3 className="class-day-title">{longDayLabel(day)}{day === today && <span className="class-day-today">Today</span>}</h3>
              {!byDay.has(day) && days.length > 0 && <p className="dir-empty muted">No classes scheduled today.</p>}
              {byDay.has(day) && <ul className="home-day-list">{byDay.get(day)?.map(entry => {
                const total = entry.sessionId ? byId.get(entry.sessionId) : undefined;
                const percent = total ? attendancePercent(total) : null;
                const body = <>
                  <span className="home-entry-time">{timeRange({ ...entry, timeZone: ACADEMY_TIME_ZONE })}</span>
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
              })}</ul>}
          </div>)}
          </ClassScheduleScroll>
        </section>
      </div>
      <ClassTypes month={month} through={through} now={now} sessions={monthSessions} totals={byId}
        sessionsFailed={!sessions.ok} totalsFailed={totals?.ok === false} />
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

function ClassTypes({ month, through, now, sessions, totals, sessionsFailed, totalsFailed }: {
  month: string; through: string | null; now: number; sessions: ClassSession[];
  totals: ReadonlyMap<string, ClassAttendanceTotal>; sessionsFailed: boolean; totalsFailed: boolean;
}) {
  const groups = monthlyClassTypes(sessions, now).map(([key, group]) => ({ ...group, key,
    average: averageClassAttendance(group.sessions, totals),
  }));

  return <div className="classes-column">
    <AttendanceChart title="AVERAGE ATTENDANCE BY CLASS TYPE" failed={sessionsFailed || totalsFailed}
      bars={groups.flatMap(group => group.average == null ? [] : [{ id: group.key, label: group.title.split("|")[0].trim(),
        title: group.title, percent: group.average }])} />
    <section className="panel" aria-labelledby="class-types-title">
      <h2 className="panel-title" id="class-types-title">CLASS TYPES</h2>
      <p className="panel-sub"><strong>{monthLabel(month)}</strong>{through && ` · completed through ${formatDate(through)}`}</p>
      <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly class types and dates">
        {sessionsFailed ? <p className="session-error" role="alert">Class types couldn’t load. Please reload shortly.</p>
          : groups.length === 0 ? <p className="dir-empty muted">No completed class entries this month.</p>
          : <ul className="class-type-list">{groups.map(group => <li className="class-type" key={group.key}>
            <Link href={`/staff/sessions/types?${new URLSearchParams({ type: group.key, month })}`}>
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
