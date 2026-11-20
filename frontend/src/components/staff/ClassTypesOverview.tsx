import Link from "next/link";
import type { startClassMonth } from "@/lib/class-month-api";
import { formatDate, type ClassSession } from "@/lib/staff-forms";
import { averageClassAttendance, monthLabel, monthlyClassTypes, type ClassAttendanceTotal } from "@/lib/staff-home";
import { AttendanceChart } from "./ClassesAttendanceChart";
import { PanelRetry } from "./PanelRetry";

export async function ClassTypesPanel({ requests, month, through, now, standalone }: {
  requests: ReturnType<typeof startClassMonth>; month: string; through: string | null; now: number; standalone?: boolean;
}) {
  const [sessions, totals] = await Promise.all([requests.sessions, requests.totals]);
  return <ClassTypesOverview month={month} through={through} now={now} sessions={sessions.ok ? sessions.data : []}
    totals={new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : [])}
    sessionsFailed={!sessions.ok} totalsFailed={totals?.ok === false} standalone={standalone} />;
}

export function ClassTypesOverview({ month, through, now, sessions, totals, sessionsFailed, totalsFailed, standalone = false }: {
  month: string; through: string | null; now: number; sessions: ClassSession[];
  totals: ReadonlyMap<string, ClassAttendanceTotal>; sessionsFailed: boolean; totalsFailed: boolean; standalone?: boolean;
}) {
  const groups = monthlyClassTypes(sessions, now).map(([key, group]) => ({ ...group, key,
    average: averageClassAttendance(group.sessions, totals),
  }));

  return <div className="classes-column">
    <AttendanceChart title="AVERAGE ATTENDANCE BY CLASS TYPE" failed={sessionsFailed || totalsFailed}
      bars={groups.flatMap(group => group.average == null ? [] : [{ id: group.key, label: group.title.split("|")[0].trim(),
        title: group.title, percent: group.average }])} />
    <section className="panel" aria-label={standalone ? "Completed class types" : undefined}
      aria-labelledby={standalone ? undefined : "class-types-title"}>
      {!standalone && <h2 className="panel-title" id="class-types-title">CLASS TYPES</h2>}
      <p className="panel-sub"><strong>{monthLabel(month)}</strong>{through && ` · completed through ${formatDate(through)}`}</p>
      <div className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly class types and dates">
        {sessionsFailed ? <p className="session-error" role="alert">Class types couldn’t load. <PanelRetry /></p>
          : groups.length === 0 ? <p className="dir-empty muted">No completed class entries this month.</p>
          : <ul className="class-type-list">{groups.map(group => <li className="class-type" key={group.key}>
            <Link href={`/staff/sessions/types?${new URLSearchParams({ type: group.key, month, ...(standalone ? { source: "class-types" } : {}) })}`}>
              <span>{group.title}</span>
              <span className="class-type-count">{group.sessions.length} {group.sessions.length === 1 ? "session" : "sessions"}
                {" · "}<strong className="class-attendance-percent">{group.average == null ? "—" : `${group.average}% avg`}</strong></span>
            </Link>
          </li>)}</ul>}
      </div>
    </section>
  </div>;
}
