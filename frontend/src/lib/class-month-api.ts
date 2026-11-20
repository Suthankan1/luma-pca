import { staffApi } from "./staff-api";
import type { ClassSession } from "./staff-forms";
import type { classMonthRange, ClassAttendanceTotal } from "./staff-home";

export async function loadClassMonth({ first, through, fromInstant, toInstant }: ReturnType<typeof classMonthRange>) {
  const sessionsQuery = new URLSearchParams({ from: fromInstant, to: toInstant });
  const [sessions, totals] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${sessionsQuery}`, undefined, 25000),
    through ? staffApi<ClassAttendanceTotal[]>(`/api/v1/attendance/class-totals?${new URLSearchParams({ from: first, to: through })}`, undefined, 25000) : null,
  ]);
  return { sessions, totals };
}
