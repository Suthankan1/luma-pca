import { StudentCalendar } from "@/components/StudentCalendar";
import { requirePortal } from "@/lib/auth-server";
import { academyToday, isRealDate } from "@/lib/staff-forms";
import { classMonthRange, gridRange, monthWeeks } from "@/lib/staff-home";
import { studentTimetable } from "@/lib/student-timetable";

export const metadata = { title: "Calendar · LUMA" };

export default async function CalendarPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  await requirePortal("student");
  const query = await searchParams;
  const today = academyToday();
  const { month, first } = classMonthRange(query.month, today, query.from);
  const range = gridRange(month);
  const selectedDay = isRealDate(query.day) && query.day >= range.first && query.day < range.after ? query.day
    : today.startsWith(month) ? today : first;
  const timetable = studentTimetable(range.fromInstant, range.toInstant);

  return <>
    <header className="student-live-card student-section-hero student-portal-enter">
      <div><h1>Calendar</h1><p>Class times in Sri Lanka time</p></div>
    </header>
    <Suspense key={month} fallback={<div className="panel" role="status">Loading calendar…</div>}>
      <CalendarContent request={timetable} month={month} today={today} selectedDay={selectedDay} />
    </Suspense>
  </>;
}

async function CalendarContent({ request, month, today, selectedDay }: {
  request: ReturnType<typeof studentTimetable>; month: string; today: string; selectedDay: string;
}) {
  const timetable = await request;
  return <>
    {timetable.unavailable && <p className="session-error" role="alert">Part of your timetable couldn’t load. Refresh to try again.</p>}
    <StudentCalendar key={`${month}:${selectedDay}`} month={month} today={today} selectedDay={selectedDay}
      weeks={monthWeeks(month)} entries={timetable.entries} />
  </>;
}
import { Suspense } from "react";
