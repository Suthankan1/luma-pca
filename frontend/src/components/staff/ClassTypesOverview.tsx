import Link from "next/link";
import type { startClassMonth } from "@/lib/class-month-api";
import { formatDate, type ClassSession } from "@/lib/staff-forms";
import { averageClassAttendance, monthLabel, monthlyClassTypes, type ClassAttendanceTotal } from "@/lib/staff-home";
import { AttendanceChart } from "./ClassesAttendanceChart";
import { PanelRetry } from "./PanelRetry";
import { ClassTypeRosterControls, type readClassTypeRosters } from "./ClassTypeRosterPanel";
import type { AcademyClass } from "@/lib/academy";

type Roster = Awaited<ReturnType<typeof readClassTypeRosters>>;

export async function ClassTypesPanel({ requests, month, through, now, standalone, rosters }: {
  requests: ReturnType<typeof startClassMonth>; month: string; through: string | null; now: number; standalone?: boolean;
  rosters?: Promise<Roster>;
}) {
  const [sessions, totals, roster] = await Promise.all([requests.sessions, requests.totals, rosters]);
  return <ClassTypesOverview month={month} through={through} now={now} sessions={sessions.ok ? sessions.data : []}
    totals={new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : [])}
    sessionsFailed={!sessions.ok} totalsFailed={totals?.ok === false} standalone={standalone} roster={roster} />;
}

function matchAcademyClass(sessions: ClassSession[], roster: Roster): AcademyClass | undefined {
  const names = new Set(sessions.map(session => session.zoomWebinarId && roster.monitorClasses.get(session.zoomWebinarId)).filter(Boolean));
  if (names.size > 0) return names.size === 1 ? roster.classes?.find(item => names.has(item.className)) : undefined;
  const normalize = (value: string) => value.trim().replace(/\s+/g, " ").toLowerCase();
  const titles = new Set(sessions.map(session => normalize(session.title)));
  const matches = roster.classes?.filter(item => titles.has(normalize(item.className)) ||
    (item.batch && sessions.some(session => session.batches.some(batch => batch.batchId === item.batch?.id))));
  return matches?.length === 1 ? matches[0] : undefined;
}

export function ClassTypesOverview({ month, through, now, sessions, totals, sessionsFailed, totalsFailed, standalone = false, roster }: {
  month: string; through: string | null; now: number; sessions: ClassSession[];
  totals: ReadonlyMap<string, ClassAttendanceTotal>; sessionsFailed: boolean; totalsFailed: boolean; standalone?: boolean;
  roster?: Roster;
}) {
  const groups = monthlyClassTypes(sessions, now).map(([key, group]) => ({ ...group, key,
    average: averageClassAttendance(group.sessions, totals),
  }));
  const rows = groups.map(group => ({ ...group, academy: roster ? matchAcademyClass(group.sessions, roster) : undefined }));
  const matched = new Set(rows.map(row => row.academy?.className));
  const remaining = roster?.classes?.filter(item => !matched.has(item.className)) ?? [];

  return <div className="classes-column">
    <AttendanceChart title="AVERAGE ATTENDANCE BY CLASS TYPE" failed={sessionsFailed || totalsFailed}
      bars={groups.flatMap(group => group.average == null ? [] : [{ id: group.key, label: group.title.split("|")[0].trim(),
        title: group.title, percent: group.average }])} />
    <section className="panel" aria-label={standalone ? "Completed class types" : undefined}
      aria-labelledby={standalone ? undefined : "class-types-title"}>
      {!standalone && <h2 className="panel-title" id="class-types-title">CLASS TYPES</h2>}
      <p className="panel-sub"><strong>{monthLabel(month)}</strong>{through && ` · completed through ${formatDate(through)}`}</p>
      {roster?.classes === null && <p className="session-error" role="alert">The Academy roster database couldn’t be reached. Batch selection and sync are unavailable. <PanelRetry /></p>}
      {roster?.batchesFailed && <p className="session-error" role="alert">The batch list couldn’t load. <PanelRetry /></p>}
      <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly class types and dates">
        {sessionsFailed && <p className="session-error" role="alert">Class types couldn’t load. <PanelRetry /></p>}
        {!sessionsFailed && rows.length === 0 && remaining.length === 0 && <p className="dir-empty muted">No class types to show.</p>}
        {(rows.length > 0 || remaining.length > 0) && <ul className="class-type-list">{rows.map((group, index) => <li className="class-type" key={group.key} aria-label={group.title}>
            <Link href={`/staff/sessions/types?${new URLSearchParams({ type: group.key, month, ...(standalone ? { source: "class-types" } : {}) })}`}>
              <span>{group.title}</span>
              <span className="class-type-count">{group.sessions.length} {group.sessions.length === 1 ? "session" : "sessions"}
                {" · "}<strong className="class-attendance-percent">{group.average == null ? "—" : `${group.average}% avg`}</strong></span>
            </Link>
            {roster && <ClassTypeRosterControls item={group.academy} slug={`completed-${index}`} roster={roster} />}
          </li>)}
          {roster && remaining.map((item, index) => <li className="class-type" key={item.className} aria-label={item.className}>
            <h3 className="form-title class-type-title">{item.className}</h3>
            <ClassTypeRosterControls item={item} slug={`academy-${index}`} roster={roster} />
          </li>)}</ul>}
      </div>
    </section>
  </div>;
}
