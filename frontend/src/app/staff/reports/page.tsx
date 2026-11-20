import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { reportSessions } from "@/lib/report-data";
import { reportPeriod, type ReportKind } from "@/lib/report-period";
import { hasPortal } from "@/lib/session-policy";
import { ACADEMY_TIME_ZONE, academyToday, instantParts } from "@/lib/staff-forms";
import { classTypeKey, compactClassTitle, monthLabel } from "@/lib/staff-home";
import { readStudentDirectory } from "@/lib/student-month-api";

export const metadata = { title: "Reports · LUMA" };

type Option = { value: string; label: string };

export default async function ReportsPage({ searchParams }: {
  searchParams: Promise<{ month?: string | string[]; endMonth?: string | string[] }>;
}) {
  const identity = await requirePortal("staff");
  const { month, endMonth } = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const selected = reportPeriod(month, endMonth, today);
  const currentMonth = today.slice(0, 7);
  const period = selected ?? reportPeriod(undefined, undefined, today)!;
  const [sessions, students] = selected ? await Promise.all([reportSessions(period, now), readStudentDirectory()]) : [null, null];
  const types = new Map<string, string>();
  for (const session of sessions ?? []) {
    const key = classTypeKey(session.title);
    if (!types.has(key)) types.set(key, compactClassTitle(session.title));
  }

  return <StaffShell active="reports" crumb="Teaching" title="Reports"
    subtitle={period.first === period.last ? monthLabel(period.first) : `${monthLabel(period.first)} – ${monthLabel(period.last)}`}
    hasStudentPortal={hasPortal(identity, "student")}>
    <section className="panel" aria-label="Report period">
      <form action="/staff/reports" className="class-student-tools report-period">
        <label>Start month<input name="month" type="month" defaultValue={period.first} max={currentMonth} required /></label>
        <label>End month <input name="endMonth" type="month" defaultValue={typeof endMonth === "string" && selected ? endMonth : ""}
          max={currentMonth} /></label>
        <button className="action secondary" type="submit">Apply months</button>
      </form>
      <p className="panel-sub">Leave End month empty for one month. Choose up to 12 months.</p>
      {!selected && <p className="session-error" role="alert">Choose a valid period ending in or before the current month.</p>}
    </section>
    {selected && <div className="report-options panel-gap">
      <ReportSelection kind="session" title="Class session report" label="Class session" empty="No completed sessions in this period."
        month={period.first} endMonth={period.last} options={sessions?.map(session => {
          const { date, time } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
          return { value: session.id, label: `${date} ${time} · ${compactClassTitle(session.title)}` };
        }) ?? null} />
      <ReportSelection kind="class-type" title="Class type report" label="Class type" empty="No completed class types in this period."
        month={period.first} endMonth={period.last} options={sessions ? [...types].map(([value, label]) => ({ value, label }))
          .sort((a, b) => a.label.localeCompare(b.label)) : null} />
      <ReportSelection kind="student" title="Student report" label="Student" empty="No students in the directory."
        month={period.first} endMonth={period.last} options={students?.map(student => ({
          value: student.id, label: `${student.studentNumber} · ${student.fullName}`,
        })) ?? null} />
    </div>}
  </StaffShell>;
}

function ReportSelection({ kind, title, label, options, empty, month, endMonth }: {
  kind: ReportKind; title: string; label: string; options: Option[] | null; empty: string; month: string; endMonth: string;
}) {
  return <section className="panel" aria-labelledby={`report-${kind}`}>
    <h2 className="panel-title" id={`report-${kind}`}>{title}</h2>
    {options === null ? <p className="session-error" role="alert">The options couldn’t load.</p>
      : options.length === 0 ? <p className="panel-sub">{empty}</p>
      : <form className="report-selection" action="/staff/reports/download">
        <input type="hidden" name="kind" value={kind} />
        <input type="hidden" name="month" value={month} />
        <input type="hidden" name="endMonth" value={endMonth} />
        <label htmlFor={`report-select-${kind}`}>{label}</label>
        <select id={`report-select-${kind}`} name="selection" defaultValue="" required>
          <option value="" disabled>Select {label.toLowerCase()}</option>
          {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
        <button className="action" type="submit">Download Excel</button>
      </form>}
  </section>;
}
