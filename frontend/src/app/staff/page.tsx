import Link from "next/link";
import type { CSSProperties } from "react";
import { StaffShell } from "@/components/StaffShell";
import { LiveRefresh } from "@/components/LiveRefresh";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, addDays, isAcademyManager, zonedToInstant, type AttendanceAppeal, type ClassSession, type PendingAttendance,
  type ProvisionalAttendance } from "@/lib/staff-forms";
import { renderTime, type Dashboard } from "@/lib/insights";
import { type WebinarMonitor } from "@/lib/webinars";
import { calendarEntries, chosenDay, chosenMonth, entryStateLabels, greeting, gridRange, homeHref, joinedLabel, liveNumbers,
  longDayLabel, monthLabel, monthWeeks, nextEntry, relativeDayName, shiftMonth, startedAgoLabel, startsInLabel, timeRange,
  type DayEntry, type LiveNumbers } from "@/lib/staff-home";

const weekdayShort = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
/** At most this many classes running at once get their own live card (each costs one attendance request). */
const MAX_LIVE_CARDS = 3;

function sessionsIn(fromInstant: string, toInstant: string) {
  return staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: fromInstant, to: toInstant })}`);
}

export default async function StaffPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const manager = isAcademyManager(identity.roles);
  const analystOnly = !manager && identity.roles.includes("ANALYST")
    && !identity.roles.some(role => role === "INSTRUCTOR" || role === "ATTENDANCE_OFFICER");
  const now = renderTime();
  const today = academyToday(new Date(now));
  const day = chosenDay(params.day, today);
  const month = chosenMonth(params.month, day);
  const grid = gridRange(month);
  const dateLabel = longDayLabel(today);
  const shell = { active: "overview" as const, crumb: "", title: "Staff portal", subtitle: `${greeting(now)} · ${dateLabel}`,
    hasStudentPortal: hasPortal(identity, "student") };

  if (analystOnly) {
    return <StaffShell {...shell}>
      <div className="staff-home">
        <section className="home-hero home-hero-quiet" aria-labelledby="home-brief-title">
          <div>
            <p className="home-eyebrow">Your reports</p>
            <h2 id="home-brief-title" className="home-hero-title">Attendance by batch</h2>
          </div>
          <Link className="home-hero-link" href="/staff/attendance?section=totals">Open reports <span aria-hidden>↗</span></Link>
        </section>
      </div>
    </StaffShell>;
  }

  // Today and the next week feed the live card and "next class"; skipped when the calendar's grid already covers them.
  const soonFrom = today, soonAfter = addDays(today, 8);
  const gridCoversSoon = soonFrom >= grid.first && soonAfter <= grid.after;
  const gridCoversDay = day >= grid.first && day < grid.after;
  const [calendar, soon, chosen, pending, appeals, monitors] = await Promise.all([
    sessionsIn(grid.fromInstant, grid.toInstant),
    gridCoversSoon ? null : sessionsIn(zonedToInstant(soonFrom, "00:00", ACADEMY_TIME_ZONE), zonedToInstant(soonAfter, "00:00", ACADEMY_TIME_ZONE)),
    gridCoversDay ? null : sessionsIn(gridRange(day.slice(0, 7)).fromInstant, gridRange(day.slice(0, 7)).toInstant),
    manager ? staffApi<PendingAttendance>("/api/v1/attendance/pending") : null,
    manager ? staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN") : null,
    manager ? staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors") : null,
  ]);

  const monitorList = monitors?.ok ? monitors.data : null;
  const liveRuns = new Map((monitorList ?? []).flatMap(m => m.live ? [[m.live.classSessionId, m.live] as const] : []));
  const liveIds = new Set(liveRuns.keys());
  const entriesFrom = (result: typeof calendar | null) => result?.ok ? calendarEntries(result.data, monitorList, liveIds, now) : null;
  const calendarDays = entriesFrom(calendar);
  const soonDays = gridCoversSoon ? calendarDays : entriesFrom(soon);
  const chosenDays = gridCoversDay ? calendarDays : entriesFrom(chosen);
  const dayEntries = chosenDays?.get(day) ?? [];
  const todayEntries = soonDays?.get(today) ?? [];
  const running = todayEntries.filter(e => e.sessionId && (e.state === "live" || e.state === "now")).slice(0, MAX_LIVE_CARDS);
  const upcomingSoon = soonDays ? new Map([...soonDays].filter(([date]) => date >= today && date < soonAfter)) : null;
  const next = upcomingSoon ? nextEntry(upcomingSoon, now) : null;
  const endedOnDay = day <= today && dayEntries.some(e => e.state === "ended" && e.sessionId);

  // Second round: live numbers for running classes, and joined/expected for the chosen day's finished classes.
  const [liveAttendance, dayDashboard] = await Promise.all([
    Promise.all(running.map(e => staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${e.sessionId}/attendance`))),
    endedOnDay ? staffApi<Dashboard>(`/api/v1/attendance/dashboard?${new URLSearchParams({ from: day, to: day })}`) : null,
  ]);
  const liveCards = running.map((entry, i) => {
    const attendance = liveAttendance[i];
    const zoomRun = liveRuns.get(entry.sessionId!);
    return { entry, numbers: attendance.ok ? liveNumbers(attendance.data.students, zoomRun?.connectedNow ?? null) : null };
  });
  const finished = new Map(dayDashboard?.ok ? dayDashboard.data.classes.map(c => [c.id, c] as const) : []);
  const tasks = [
    { label: "Classes ready to finalise", count: pending?.ok ? pending.data.classes.filter(item => item.state === "READY").length : 0 },
    { label: "Appeals waiting for a decision", count: appeals?.ok ? appeals.data.length : 0 },
  ].filter(task => task.count > 0);
  const refreshUntil = new Date(now + 3 * 3600_000).toISOString();
  const href = (options: { day?: string; month?: string }) => homeHref({ day: options.day ?? day, month: options.month ?? month }, today);

  return <StaffShell {...shell} actions={<Link className="home-action" href="/staff/dashboard">Attendance reports <span aria-hidden>→</span></Link>}>
    <div className="staff-home">
      {liveCards.length > 0
        ? <section className="home-hero home-hero-live" aria-labelledby="live-title">
            <h2 id="live-title" className="sr-only">Live now</h2>
            {liveCards.map(({ entry, numbers }) => <LiveCard key={entry.key} entry={entry} numbers={numbers} now={now} />)}
            <LiveRefresh until={refreshUntil} seconds={30} label="while a class runs" />
          </section>
        : <section className="home-hero" aria-labelledby="live-title">
            <div className="home-hero-copy">
              <p className="home-eyebrow">No class is live right now</p>
              <h2 id="live-title" className="home-hero-title">
                {next ? <Link href={next.href} className="home-hero-next"><OneLineTitle text={next.title} /></Link> : "Nothing else scheduled this week"}
              </h2>
              {next && <p className="home-hero-meta">
                <span className="home-chip home-chip-soft">Next class</span>
                {relativeDayName(dayOf(next), today)} · {timeRange(next)} · {startsInLabel(next.startsAt, now)}
              </p>}
            </div>
            <Link className="home-hero-link" href="/staff/sessions">View all classes <span aria-hidden>↗</span></Link>
          </section>}

      <div className={manager ? "home-panels" : "home-panels home-panels-single"}>
        <section className="home-card home-day" aria-labelledby="day-title">
          <div className="home-card-heading">
            <h2 id="day-title"><span className="sr-only">Classes on </span>{relativeDayName(day, today)}{" "}
              <span className="home-day-date">{longDayLabel(day).replace(/^\S+ /, "")}</span></h2>
            <nav className="home-daynav" aria-label="Choose a day">
              <Link scroll={false} href={href({ day: addDays(day, -1), month: addDays(day, -1).slice(0, 7) })} aria-label="Previous day">‹</Link>
              {day !== today && <Link scroll={false} href={href({ day: today, month: today.slice(0, 7) })} className="home-daynav-today">Today</Link>}
              <Link scroll={false} href={href({ day: addDays(day, 1), month: addDays(day, 1).slice(0, 7) })} aria-label="Next day">›</Link>
            </nav>
          </div>
          {chosenDays && dayEntries.length > 0 && <ul className="home-list home-day-list" key={day}>
            {dayEntries.map(entry => {
              const done = entry.sessionId ? finished.get(entry.sessionId) : undefined;
              return <li key={entry.key} className={`home-entry home-entry-${entry.state}`}>
                <Link href={entry.href}>
                  <span className="home-entry-time">{timeRange(entry)}</span>
                  <span className="home-entry-copy">
                    <OneLineTitle text={entry.title} />
                    <span className="home-entry-meta">{entry.source === "zoom" ? "On Zoom, not in the timetable yet"
                      : entry.state === "ended" && done ? joinedLabel(done) : entryStateLabels[entry.state]}</span>
                  </span>
                  <span className={`home-chip home-chip-${entry.state}`}>{entryStateLabels[entry.state]}</span>
                </Link>
              </li>;
            })}
          </ul>}
          {chosenDays && dayEntries.length === 0 && <p className="home-empty">No classes on this day.</p>}
          {!chosenDays && <p className="home-empty">{(calendar.status === 403) ? "Your role has no classes." : "Classes couldn’t load. Please reload shortly."}</p>}
          {monitors && !monitors.ok && <p className="home-empty">Zoom’s live status couldn’t load.</p>}
        </section>

        {manager && <section className="home-card" aria-labelledby="home-attention-title">
          <div className="home-card-heading"><h2 id="home-attention-title">Needs attention</h2></div>
          {tasks.length > 0 && <ul className="home-list">
            {tasks.map(task => <li key={task.label}>
              <Link href="/staff/attendance" className="home-task">
                <strong>{task.label}</strong><span className="home-chip home-chip-count">{task.count}</span>
              </Link>
            </li>)}
          </ul>}
          {(!pending?.ok || !appeals?.ok) && <p className="home-empty">Some attendance tasks couldn’t load.</p>}
          {pending?.ok && appeals?.ok && tasks.length === 0 && <p className="home-empty home-all-clear">All caught up. Nothing to review.</p>}
        </section>}
      </div>

      <section className="home-card home-calendar" aria-labelledby="calendar-title">
        <div className="home-card-heading">
          <h2 id="calendar-title">{monthLabel(month)}</h2>
          <nav className="home-daynav" aria-label="Choose a month">
            <Link scroll={false} href={href({ month: shiftMonth(month, -1) })} aria-label="Previous month">‹</Link>
            {month !== today.slice(0, 7) && <Link scroll={false} href={href({ day: today, month: today.slice(0, 7) })} className="home-daynav-today">This month</Link>}
            <Link scroll={false} href={href({ month: shiftMonth(month, 1) })} aria-label="Next month">›</Link>
          </nav>
        </div>
        {calendarDays ? <div className="cal">
          <div className="cal-weekdays" aria-hidden="true">{weekdayShort.map(name => <span key={name}>{name}</span>)}</div>
          {monthWeeks(month).map(week => <div className="cal-week" key={week[0].date}>
            {week.map(cell => {
              const entries = calendarDays.get(cell.date) ?? [];
              const classes = ["cal-day", cell.inMonth ? "" : "cal-out", cell.date === today ? "cal-today" : "",
                cell.date === day ? "cal-selected" : "", cell.date < today ? "cal-past" : ""].filter(Boolean).join(" ");
              return <div key={cell.date} className={classes}>
                <Link scroll={false} className="cal-date" href={href({ day: cell.date })} aria-current={cell.date === day ? "date" : undefined}
                  aria-label={`${longDayLabel(cell.date)}${cell.date === today ? ", today" : ""}: ${entries.length ? `${entries.length} class${entries.length === 1 ? "" : "es"}` : "no classes"}`}>
                  {Number(cell.date.slice(8))}
                  {entries.some(e => e.state === "live") && <span className="cal-live-dot" aria-hidden />}
                </Link>
                {entries.length > 0 && <span className="cal-count" aria-hidden>{entries.length}</span>}
                <ul className="cal-entries">
                  {entries.slice(0, 2).map(entry => <li key={entry.key}>
                    <Link href={entry.href} className={`cal-entry cal-entry-${entry.state} cal-entry-${entry.source}`}>
                      <span className="cal-entry-time">{timeRange(entry).replace(/–.*/, "")}</span>
                      <OneLineTitle text={entry.title} />
                    </Link>
                  </li>)}
                  {entries.length > 2 && <li><Link scroll={false} className="cal-more" href={href({ day: cell.date })}>+{entries.length - 2} more</Link></li>}
                </ul>
              </div>;
            })}
          </div>)}
          {monitorList && <p className="cal-legend" aria-hidden="true">
            <span className="cal-key cal-key-class" />Class<span className="cal-key cal-key-zoom" />On Zoom, not in the timetable yet
          </p>}
        </div> : <p className="home-empty">The calendar couldn’t load. Please reload shortly.</p>}
      </section>
    </div>
  </StaffShell>;
}

/** The academy date an entry starts on (entries keep their own zone; the day list groups by academy date). */
function dayOf(entry: DayEntry) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(entry.startsAt));
}

function LiveCard({ entry, numbers, now }: { entry: DayEntry; numbers: LiveNumbers | null; now: number }) {
  const percent = numbers?.percent ?? null;
  return <article className="live-card" aria-label={`${entryStateLabels[entry.state]}: ${entry.title}`}>
    <div className="live-card-head">
      <span className={`live-badge ${entry.state === "live" ? "" : "live-badge-now"}`}>
        <span className="live-dot" aria-hidden />{entry.state === "live" ? "Live" : "In progress"}
      </span>
      <span className="live-since">{timeRange(entry)} · {startedAgoLabel(entry.startsAt, now)}</span>
    </div>
    <h3 className="live-title"><Link href={entry.href}><OneLineTitle text={entry.title} /></Link></h3>
    {numbers ? <div className="live-body">
      <dl className="live-stats">
        <div><dt>Expected</dt><dd>{numbers.expected}</dd></div>
        <div><dt>Joined</dt><dd>{numbers.joined}</dd></div>
        <div><dt>Online</dt><dd>{numbers.connectedNow}</dd></div>
        {/* The ring shows this on wide screens; on phones this tile does, and it is always what screen readers read. */}
        <div className="live-stat-percent"><dt>Attendance</dt><dd>{percent == null ? "—" : `${percent}%`}</dd></div>
      </dl>
      <div className="live-ring" aria-hidden="true" style={{ "--pct": percent ?? 0 } as CSSProperties}>
        <svg viewBox="0 0 36 36">
          <circle className="live-ring-track" cx="18" cy="18" r="15.915" />
          {percent ? <circle className="live-ring-fill" cx="18" cy="18" r="15.915" pathLength={100} /> : null}
        </svg>
        <span className="live-ring-value" aria-hidden="true">{percent == null ? "—" : `${percent}%`}<small>attendance</small></span>
      </div>
    </div> : <p className="live-note">Live attendance couldn’t load. Open the class for details.</p>}
    {numbers && numbers.expected === 0 && <p className="live-note">No students are expected: this class isn’t linked to a batch yet.</p>}
    <Link className="live-open" href={entry.href}>Open class <span aria-hidden>→</span></Link>
  </article>;
}
