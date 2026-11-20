import Link from "next/link";
import { Suspense } from "react";
import { StaffShell } from "@/components/StaffShell";
import { LiveRefresh } from "@/components/LiveRefresh";
import { OneLineTitle } from "@/components/staff/OneLineTitle";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, addDays, isAcademyManager, zonedToInstant, type ClassSession,
  type ProvisionalAttendance } from "@/lib/staff-forms";
import { renderTime } from "@/lib/insights";
import { type WebinarMonitor } from "@/lib/webinars";
import { calendarEntries, chosenDay, chosenMonth, entryStateLabels, greeting, gridRange, heldRange, homeHref, joinedLabel,
  liveNumbers, longDayLabel, monthLabel, monthWeeks, monthlyAttendance, nextEntry, relativeDayName, shiftMonth, shortDayLabel,
  startedAgoLabel, startsInLabel, timeRange, type ClassAttendanceTotal, type DayEntry, type LiveNumbers } from "@/lib/staff-home";

const weekdayShort = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
/** At most this many classes running at once get their own live row (each costs one attendance request). */
const MAX_LIVE = 3;
const MONTH_ATTENDANCE_TIMEOUT_MS = 25_000;

function sessionsIn(fromInstant: string, toInstant: string) {
  return staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: fromInstant, to: toInstant })}`);
}

function classTotals(range: { from: string; to: string }, timeoutMs?: number) {
  return staffApi<ClassAttendanceTotal[]>(`/api/v1/attendance/class-totals?${new URLSearchParams(range)}`, undefined, timeoutMs);
}

/** The academy date an entry starts on. */
function dayOf(entry: DayEntry) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: ACADEMY_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(entry.startsAt));
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
  const shell = { active: "overview" as const, crumb: "", title: "Staff portal", subtitle: `${greeting(now)} · ${longDayLabel(today)}`,
    hasStudentPortal: hasPortal(identity, "student") };

  if (analystOnly) {
    return <StaffShell {...shell}>
      <section className="home-strip" aria-labelledby="home-brief-title">
        <div className="home-strip-main">
          <p className="home-strip-label">Your reports</p>
          <h2 id="home-brief-title" className="home-strip-title">Attendance by batch</h2>
        </div>
        <Link className="home-button" href="/staff/attendance?section=totals">Open reports</Link>
      </section>
    </StaffShell>;
  }

  // Today and the next week feed the live row and "next class"; skipped when the calendar's grid already covers them.
  const soonAfter = addDays(today, 8);
  const gridCoversSoon = today >= grid.first && soonAfter <= grid.after;
  const gridCoversDay = day >= grid.first && day < grid.after;
  const dayGrid = gridRange(day.slice(0, 7));
  const [calendar, soon, chosen, monitors] = await Promise.all([
    sessionsIn(grid.fromInstant, grid.toInstant),
    gridCoversSoon ? null : sessionsIn(zonedToInstant(today, "00:00", ACADEMY_TIME_ZONE), zonedToInstant(soonAfter, "00:00", ACADEMY_TIME_ZONE)),
    gridCoversDay ? null : sessionsIn(dayGrid.fromInstant, dayGrid.toInstant),
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
  const running = (soonDays?.get(today) ?? []).filter(e => e.sessionId && (e.state === "live" || e.state === "now")).slice(0, MAX_LIVE);
  const next = soonDays ? nextEntry(new Map([...soonDays].filter(([date]) => date >= today && date < soonAfter)), now) : null;
  const endedOnDay = day <= today && dayEntries.some(e => e.state === "ended" && e.sessionId);

  // Second round: live numbers for running classes, and joined/expected for the chosen day's finished classes.
  const [liveAttendance, dayTotals] = await Promise.all([
    Promise.all(running.map(e => staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${e.sessionId}/attendance`))),
    endedOnDay ? classTotals({ from: day, to: day }) : null,
  ]);
  const live = running.map((entry, i) => ({ entry, numbers: liveAttendance[i].ok
    ? liveNumbers(liveAttendance[i].data.students, liveRuns.get(entry.sessionId!)?.connectedNow ?? null) : null }));
  const finished = new Map(dayTotals?.ok ? dayTotals.data.map(c => [c.id, c] as const) : []);
  const href = (options: { day?: string; month?: string }) => homeHref({ day: options.day ?? day, month: options.month ?? month }, today);
  const previousDay = addDays(day, -1), nextDay = addDays(day, 1);

  return <StaffShell {...shell} actions={<Link className="home-button home-button-quiet" href="/staff/dashboard">Attendance reports</Link>}>
    <div className="staff-home">
      {live.length > 0
        ? <section className="home-strip home-strip-live" aria-labelledby="live-title">
            <h2 id="live-title" className="sr-only">Live now</h2>
            {live.map(({ entry, numbers }) => <LiveRow key={entry.key} entry={entry} numbers={numbers} now={now} />)}
            <LiveRefresh until={new Date(now + 3 * 3600_000).toISOString()} seconds={30} label="while a class runs" />
          </section>
        : <section className="home-strip" aria-labelledby="next-title">
            <div className="home-strip-main">
              <p className="home-strip-label">{next ? "Next class" : "No class is live right now"}</p>
              <h2 id="next-title" className="home-strip-title">
                {next ? <Link href={next.href}><OneLineTitle text={next.title} /></Link> : "Nothing else scheduled this week"}
              </h2>
            </div>
            {next && <p className="home-strip-when">
              <span>{relativeDayName(dayOf(next), today)}, {timeRange(next)}</span>
              <strong>{startsInLabel(next.startsAt, now)}</strong>
            </p>}
          </section>}

      <div className="home-grid">
        <section className="home-card home-day" aria-labelledby="day-title">
          <div className="home-stepper">
            <Link scroll={false} className="home-step" href={href({ day: previousDay, month: previousDay.slice(0, 7) })}
              aria-label={`Previous day, ${shortDayLabel(previousDay)}`}>
              <span aria-hidden="true">‹</span><span className="home-step-text">{shortDayLabel(previousDay)}</span>
            </Link>
            <div className="home-stepper-now">
              <h2 id="day-title"><span className="sr-only">Classes on </span>{relativeDayName(day, today)}</h2>
              <p>{day === today ? shortDayLabel(day) : <>
                {shortDayLabel(day)} · <Link scroll={false} href={href({ day: today, month: today.slice(0, 7) })}>Back to today</Link>
              </>}</p>
            </div>
            <Link scroll={false} className="home-step home-step-next" href={href({ day: nextDay, month: nextDay.slice(0, 7) })}
              aria-label={`Next day, ${shortDayLabel(nextDay)}`}>
              <span className="home-step-text">{shortDayLabel(nextDay)}</span><span aria-hidden="true">›</span>
            </Link>
          </div>
          {chosenDays && dayEntries.length > 0 && <ul className="home-day-list" key={day}>
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
                  <span className={`home-state home-state-${entry.state}`}>{entryStateLabels[entry.state]}</span>
                </Link>
              </li>;
            })}
          </ul>}
          {chosenDays && dayEntries.length === 0 && <p className="home-empty">No classes on this day.</p>}
          {!chosenDays && <p className="home-empty">{calendar.status === 403 ? "Your role has no classes." : "Classes couldn’t load. Please reload shortly."}</p>}
          {monitors && !monitors.ok && <p className="home-empty">Zoom’s live status couldn’t load.</p>}
        </section>

        <Suspense fallback={<section className="home-card home-chart" aria-label="Attendance this month">
          <p className="home-empty">Adding up this month’s attendance…</p>
        </section>}>
          <MonthAttendance month={month} today={today} />
        </Suspense>
      </div>

      <section className="home-card home-calendar" aria-labelledby="calendar-title">
        <div className="home-stepper">
          <Link scroll={false} className="home-step" href={href({ month: shiftMonth(month, -1) })}
            aria-label={`Previous month, ${monthLabel(shiftMonth(month, -1))}`}><span aria-hidden="true">‹</span></Link>
          <div className="home-stepper-now">
            <h2 id="calendar-title">{monthLabel(month)}</h2>
            {month !== today.slice(0, 7) && <p><Link scroll={false} href={href({ day: today, month: today.slice(0, 7) })}>Back to this month</Link></p>}
          </div>
          <Link scroll={false} className="home-step home-step-next" href={href({ month: shiftMonth(month, 1) })}
            aria-label={`Next month, ${monthLabel(shiftMonth(month, 1))}`}><span aria-hidden="true">›</span></Link>
        </div>
        {calendarDays ? <div className="cal">
          <div className="cal-weekdays" aria-hidden="true">{weekdayShort.map(name => <span key={name}>{name}</span>)}</div>
          <div className="cal-grid">
            {monthWeeks(month).flat().map(cell => {
              const entries = calendarDays.get(cell.date) ?? [];
              const classes = ["cal-day", cell.inMonth ? "" : "cal-out", cell.date === today ? "cal-today" : "",
                cell.date === day ? "cal-selected" : ""].filter(Boolean).join(" ");
              return <div key={cell.date} className={classes}>
                <Link scroll={false} className="cal-date" href={href({ day: cell.date })} aria-current={cell.date === day ? "date" : undefined}
                  aria-label={`${longDayLabel(cell.date)}${cell.date === today ? ", today" : ""}: ${entries.length ? `${entries.length} class${entries.length === 1 ? "" : "es"}` : "no classes"}`}>
                  {Number(cell.date.slice(8))}
                </Link>
                {entries.length > 0 && <span className="cal-count" aria-hidden="true">{entries.length}</span>}
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
          </div>
          {monitorList && <p className="cal-legend" aria-hidden="true">
            <span className="cal-key" />Class<span className="cal-key cal-key-zoom" />On Zoom, not in the timetable yet
          </p>}
        </div> : <p className="home-empty">The calendar couldn’t load. Please reload shortly.</p>}
      </section>
    </div>
  </StaffShell>;
}

async function MonthAttendance({ month, today }: { month: string; today: string }) {
  const range = heldRange(month, today);
  const result = range ? await classTotals(range, MONTH_ATTENDANCE_TIMEOUT_MS) : null;
  const totals = result?.ok ? monthlyAttendance(result.data) : null;
  return <section className="home-card home-chart" aria-labelledby="chart-title">
    <div className="home-card-head">
      <h2 id="chart-title">Attendance in {monthLabel(month)}</h2>
      <p>Average share of expected students who joined each completed class</p>
    </div>
    {result && !result.ok && <p className="home-empty">{result.status === 403 ? "Your role has no attendance figures." : "Attendance couldn’t load. Please reload shortly."}</p>}
    {(!range || (result?.ok && result.data.length === 0)) && <p className="home-empty">No completed classes this month.</p>}
    {totals && totals.average == null && totals.withoutStudents > 0 && <p className="home-empty">No completed classes with expected students this month.</p>}
    {totals && totals.average != null && <div className="month-attendance">
      <p className="month-attendance-summary">
        <strong>{totals.average}%</strong>
        <span>{totals.classes} {totals.classes === 1 ? "class" : "classes"} counted</span>
      </p>
      <meter className="month-attendance-bar" min={0} max={100} value={totals.average} aria-label="Average attendance" />
      {totals.withoutStudents > 0 && <p className="home-note">
        {totals.withoutStudents} {totals.withoutStudents === 1 ? "class had" : "classes had"} no expected students.
      </p>}
    </div>}
  </section>;
}

function LiveRow({ entry, numbers, now }: { entry: DayEntry; numbers: LiveNumbers | null; now: number }) {
  return <article className="live-row" aria-label={`${entryStateLabels[entry.state]}: ${entry.title}`}>
    <span className={`live-badge ${entry.state === "live" ? "" : "live-badge-now"}`}>
      <span className="live-dot" aria-hidden="true" />{entry.state === "live" ? "Live" : "In progress"}
    </span>
    <div className="live-row-main">
      <h3><Link href={entry.href}><OneLineTitle text={entry.title} /></Link></h3>
      <p>{timeRange(entry)} · {startedAgoLabel(entry.startsAt, now)}</p>
    </div>
    {numbers ? <dl className="live-figures">
      <div><dt>Joined</dt><dd>{numbers.joined}<small> / {numbers.expected}</small></dd></div>
      <div><dt>Attendance</dt><dd>{numbers.percent == null ? "—" : `${numbers.percent}%`}</dd></div>
      <div><dt>Online</dt><dd>{numbers.connectedNow}</dd></div>
    </dl> : <p className="live-row-note">Live numbers couldn’t load.</p>}
  </article>;
}
