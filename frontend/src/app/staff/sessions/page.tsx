import Link from "next/link";
import { Suspense } from "react";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { ClassMonthPicker } from "@/components/staff/ClassMonthPicker";
import { AttendanceChart } from "@/components/staff/ClassesAttendanceChart";
import { ClassTypesOverview } from "@/components/staff/ClassTypesOverview";
import { ClassScheduleScroll } from "@/components/staff/ClassScheduleScroll";
import { AddWebinarForm, WebinarActions } from "@/components/staff/WebinarForms";
import { requirePortal } from "@/lib/auth-server";
import { loadClassMonth } from "@/lib/class-month-api";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, formatDate, instantParts } from "@/lib/staff-forms";
import { attendancePercent, calendarEntries, classMonthRange, compactClassTitle, longDayLabel, monthLabel,
  timeRange, type DayEntry } from "@/lib/staff-home";
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
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange(params.month, today, params.from);
  const { month, first, last, through } = range;
  const currentMonth = today.startsWith(month);
  const shell = { active: "sessions" as const, crumb: "Teaching", title: "Classes",
    subtitle: "Classes Zoom has scheduled for the webinars you track, and the ones already held. Times come from Zoom.",
    hasStudentPortal: hasPortal(identity, "student") };

  const [{ sessions, totals }, monitors] = await Promise.all([
    loadClassMonth(range),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
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
    <ClassMonthPicker href="/staff/sessions" month={month} currentMonth={currentMonth} />
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
      <ClassTypesOverview month={month} through={through} now={now} sessions={monthSessions} totals={byId}
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
