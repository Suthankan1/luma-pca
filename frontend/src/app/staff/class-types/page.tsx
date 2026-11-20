import { StaffShell } from "@/components/StaffShell";
import { ClassMonthPicker } from "@/components/staff/ClassMonthPicker";
import { ClassTypesOverview } from "@/components/staff/ClassTypesOverview";
import { requirePortal } from "@/lib/auth-server";
import { loadClassMonth } from "@/lib/class-month-api";
import { renderTime } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { academyToday } from "@/lib/staff-forms";
import { classMonthRange } from "@/lib/staff-home";

export const metadata = { title: "Class Types · LUMA" };

export default async function ClassTypesPage({ searchParams }: { searchParams: Promise<{ month?: string | string[] }> }) {
  const identity = await requirePortal("staff");
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange((await searchParams).month, today);
  const { sessions, totals } = await loadClassMonth(range);

  return <StaffShell active="class-types" crumb="Teaching" title="Class Types" subtitle="Completed sessions and attendance by class type."
    hasStudentPortal={hasPortal(identity, "student")}>
    <ClassMonthPicker href="/staff/class-types" month={range.month} currentMonth={today.startsWith(range.month)} />
    <ClassTypesOverview standalone month={range.month} through={range.through} now={now} sessions={sessions.ok ? sessions.data : []}
      totals={new Map(totals?.ok ? totals.data.map(total => [total.id, total]) : [])}
      sessionsFailed={!sessions.ok} totalsFailed={totals?.ok === false} />
  </StaffShell>;
}
