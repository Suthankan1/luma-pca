import Link from "next/link";
import { Suspense } from "react";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { renderTime } from "@/lib/insights";
import { reportSessions } from "@/lib/report-data";
import { isReportKind, reportPeriod, type ReportKind, type ReportPeriod } from "@/lib/report-period";
import { hasPortal } from "@/lib/session-policy";
import { ACADEMY_TIME_ZONE, academyToday, instantParts } from "@/lib/staff-forms";
import { classTypeKey, compactClassTitle, monthLabel } from "@/lib/staff-home";
import { readStudentDirectory } from "@/lib/student-month-api";
import { ReportSelection } from "./report-selection";

export const metadata = { title: "Reports · LUMA" };

const reportChoices = [
  { kind: "session", title: "Class session report", label: "One completed class" },
  { kind: "class-type", title: "Class type report", label: "Sessions of one class type" },
  { kind: "student", title: "Student report", label: "One student’s attendance" },
] as const;

export default async function ReportsPage({ searchParams }: {
  searchParams: Promise<{ month?: string | string[]; endMonth?: string | string[]; kind?: string | string[] }>;
}) {
  const identity = await requirePortal("staff");
  const { month, endMonth, kind } = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const selected = reportPeriod(month, endMonth, today);
  const currentMonth = today.slice(0, 7);
  const period = selected ?? reportPeriod(undefined, undefined, today)!;
  const active = typeof kind === "string" && isReportKind(kind) ? kind : null;

  return <StaffShell active="reports" crumb="Teaching" title="Reports"
    subtitle={period.first === period.last ? monthLabel(period.first) : `${monthLabel(period.first)} – ${monthLabel(period.last)}`}
    hasStudentPortal={hasPortal(identity, "student")}>
    <section className="panel" aria-label="Report period">
      <form action="/staff/reports" className="class-student-tools report-period">
        {active && <input type="hidden" name="kind" value={active} />}
        <label>Start month<input name="month" type="month" defaultValue={period.first} max={currentMonth} required /></label>
        <label>End month <input name="endMonth" type="month" defaultValue={typeof endMonth === "string" && selected ? endMonth : ""}
          max={currentMonth} /></label>
        <button className="action secondary" type="submit">Apply months</button>
      </form>
      <p className="panel-sub">Leave End month empty for one month. Choose up to 12 months.</p>
      {!selected && <p className="session-error" role="alert">Choose a valid period ending in or before the current month.</p>}
    </section>
    {selected && <>
      <nav className="report-options panel-gap" aria-label="Report types">
        {reportChoices.map(choice => <Link key={choice.kind} prefetch={false} scroll={false}
          className="panel report-type-card" aria-current={active === choice.kind ? "page" : undefined}
          href={`/staff/reports?${new URLSearchParams({ kind: choice.kind, month: period.first,
            ...(period.first === period.last ? {} : { endMonth: period.last }) })}`}>
          <strong>{choice.title}</strong><span>{choice.label}</span>
        </Link>)}
      </nav>
      {active && <div className="panel-gap">
        <Suspense key={`${active}/${period.first}/${period.last}`} fallback={<p className="panel" role="status">Loading report options…</p>}>
          <ReportOptions kind={active} period={period} now={now} />
        </Suspense>
      </div>}
    </>}
  </StaffShell>;
}

async function ReportOptions({ kind, period, now }: { kind: ReportKind; period: ReportPeriod; now: number }) {
  const months = { month: period.first, endMonth: period.last };
  if (kind === "student") {
    const students = await readStudentDirectory();
    return <ReportSelection {...months} kind={kind} title="Choose a student" label="Student" empty="No students in the directory."
      options={students?.map(student => ({ value: student.id, label: student.fullName, detail: student.studentNumber })) ?? null} />;
  }
  const sessions = await reportSessions(period, now);
  if (kind === "session") {
    return <ReportSelection {...months} kind={kind} title="Choose a class session" label="Class session" empty="No completed sessions in this period."
      options={sessions?.map(session => {
        const { date, time } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
        return { value: session.id, label: compactClassTitle(session.title), detail: `${date} · ${time}` };
      }) ?? null} />;
  }
  const types = new Map<string, string>();
  for (const session of sessions ?? []) {
    const key = classTypeKey(session.title);
    if (!types.has(key)) types.set(key, compactClassTitle(session.title));
  }
  return <ReportSelection {...months} kind={kind} title="Choose a class type" label="Class type" empty="No completed class types in this period."
    options={sessions ? [...types].map(([value, label]) => ({ value, label })).sort((a, b) => a.label.localeCompare(b.label)) : null} />;
}
