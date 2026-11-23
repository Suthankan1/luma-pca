import Link from "next/link";
import { Suspense } from "react";
import type { startClassMonth } from "@/lib/class-month-api";
import { ACADEMY_TIME_ZONE, formatDate, instantParts, type ClassSession } from "@/lib/staff-forms";
import { averageClassAttendance, classTypeKey, compactClassTitle, monthLabel, monthlyClassTypes, type ClassAttendanceTotal } from "@/lib/staff-home";
import { AttendanceChart } from "./ClassesAttendanceChart";
import { PanelRetry } from "./PanelRetry";
import { ClassTypeRosterControls, matchClassTypeWebinar, type readClassTypeRosters } from "./ClassTypeRosterPanel";
import type { WebinarMonitor } from "@/lib/webinars";

type RosterRequests = ReturnType<typeof readClassTypeRosters>;
type Roster = { classes: RosterRequests["classes"]; monitors: WebinarMonitor[]; monitorsFailed: boolean };
type MonthlyRow = { key: string; title: string; sessions: ClassSession[]; average: number | null; monitor?: WebinarMonitor; webinarId?: string };

export async function ClassTypesPanel({ requests, month, through, now, standalone, rosters }: {
  requests: ReturnType<typeof startClassMonth>; month: string; through: string | null; now: number; standalone?: boolean;
  rosters?: RosterRequests;
}) {
  const [sessions, totals, monitors] = await Promise.all([requests.sessions, requests.totals, rosters?.monitors]);
  const roster = rosters && { classes: rosters.classes, monitors: monitors?.ok ? monitors.data : [], monitorsFailed: monitors?.ok === false };
  return <ClassTypesOverview month={month} through={through} now={now} sessions={sessions.ok ? sessions.data : []}
    totals={new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : [])}
    sessionsFailed={!sessions.ok} totalsFailed={totals?.ok === false} standalone={standalone} roster={roster} />;
}

function scheduledRows(month: string, sessions: ClassSession[], completed: MonthlyRow[], roster: Roster) {
  const rows = new Map(completed.map(group => [group.key, { ...group, ...matchClassTypeWebinar(group.sessions, roster.monitors) }]));
  const scheduled = new Map<string, ClassSession[]>();
  const inMonth = (start: string | null) => !!start && Number.isFinite(Date.parse(start)) &&
    instantParts(start, ACADEMY_TIME_ZONE).date.startsWith(`${month}-`);
  for (const session of sessions) {
    if (session.noAttendees || session.status !== "SCHEDULED" || !inMonth(session.startsAt)) continue;
    const key = classTypeKey(session.title);
    const group = scheduled.get(key);
    if (group) group.push(session);
    else scheduled.set(key, [session]);
  }
  for (const [key, group] of scheduled) {
    const row = rows.get(key) ?? { key, title: compactClassTitle(group[0].title), sessions: [], average: null };
    rows.set(key, { ...row, ...matchClassTypeWebinar([...row.sessions, ...group], roster.monitors) });
  }
  const represented = new Set([...rows.values()].flatMap(row => row.monitor ? [row.monitor.id] : []));
  for (const monitor of roster.monitors) {
    if (!monitor.active) continue;
    const scheduledInMonth = monitor.occurrences.some(slot => slot.status?.toLowerCase() !== "deleted" && inMonth(slot.startTime))
      || (!monitor.recurring && monitor.occurrences.length === 0 && inMonth(monitor.scheduledStartAt));
    if (!scheduledInMonth) continue;
    const topic = monitor.topic ?? "Zoom webinar";
    if (represented.has(monitor.id)) continue;
    const baseKey = classTypeKey(topic);
    const key = rows.has(baseKey) ? monitor.id : baseKey;
    rows.set(key, { key, title: compactClassTitle(topic), sessions: [], average: null, monitor, webinarId: monitor.webinarId });
  }
  return [...rows.values()].sort((a, b) => a.title.localeCompare(b.title));
}

export function ClassTypesOverview({ month, through, now, sessions, totals, sessionsFailed, totalsFailed, standalone = false, roster }: {
  month: string; through: string | null; now: number; sessions: ClassSession[];
  totals: ReadonlyMap<string, ClassAttendanceTotal>; sessionsFailed: boolean; totalsFailed: boolean; standalone?: boolean;
  roster?: Roster;
}) {
  const groups = monthlyClassTypes(sessions, now).map(([key, group]) => ({ ...group, key,
    average: averageClassAttendance(group.sessions, totals),
  }));
  const rows: MonthlyRow[] = roster ? scheduledRows(month, sessions, groups, roster) : groups;

  return <div className="classes-column">
    <AttendanceChart title="AVERAGE ATTENDANCE BY CLASS TYPE" failed={sessionsFailed || totalsFailed}
      bars={groups.flatMap(group => group.average == null ? [] : [{ id: group.key, label: group.title.split("|")[0].trim(),
        title: group.title, percent: group.average }])} />
    <section className="panel" aria-label={standalone ? "Class types for the selected month" : undefined}
      aria-labelledby={standalone ? undefined : "class-types-title"}>
      {!standalone && <h2 className="panel-title" id="class-types-title">CLASS TYPES</h2>}
      <p className="panel-sub"><strong>{monthLabel(month)}</strong>{through && ` · completed through ${formatDate(through)}`}</p>
      {roster && <Suspense fallback={null}><PaymentSourceStatus classes={roster.classes} /></Suspense>}
      {roster?.monitorsFailed && <p className="session-error" role="alert">Zoom schedules couldn’t load. <PanelRetry /></p>}
      <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly class types and dates">
        {sessionsFailed && <p className="session-error" role="alert">Class types couldn’t load. <PanelRetry /></p>}
        {!sessionsFailed && rows.length === 0 && <p className="dir-empty muted">{standalone
          ? "No class types scheduled for this month." : "No completed class entries this month."}</p>}
        {rows.length > 0 && <ul className="class-type-list">{rows.map(group => <li className="class-type" key={group.key} aria-label={group.title}>
            {group.sessions.length > 0 ? <Link href={`/staff/sessions/types?${new URLSearchParams({ type: group.key, month, ...(standalone ? { source: "class-types" } : {}) })}`}>
              <span>{group.title}</span>
              <span className="class-type-count">{group.sessions.length} {group.sessions.length === 1 ? "session" : "sessions"}
                {" · "}<strong className="class-attendance-percent">{group.average == null ? "—" : `${group.average}% avg`}</strong></span>
            </Link> : <h3 className="form-title class-type-title">{group.title}</h3>}
            {roster && <Suspense fallback={<p className="class-type-roster-inline muted">Loading payment classes…</p>}>
              <ClassTypeRosterControls monitor={group.monitor} webinarId={group.webinarId} classes={roster.classes} />
            </Suspense>}
          </li>)}</ul>}
      </div>
    </section>
  </div>;
}

async function PaymentSourceStatus({ classes }: { classes: RosterRequests["classes"] }) {
  if ((await classes).ok) return null;
  return <p className="session-error" role="alert">The Academy roster database couldn’t be reached. Payment class selection and sync are unavailable. <PanelRetry /></p>;
}
