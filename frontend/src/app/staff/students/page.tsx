import { Suspense } from "react";
import { redirect } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { StudentMonthSkeleton } from "@/components/staff/AttendanceSkeleton";
import { PanelRetry } from "@/components/staff/PanelRetry";
import { ClassMonthPicker } from "@/components/staff/ClassMonthPicker";
import { requirePortal } from "@/lib/auth-server";
import { renderTime, type Dashboard } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, studentListHref } from "@/lib/staff-forms";
import { classMonthRange, monthLabel } from "@/lib/staff-home";
import { compileStudentMonth } from "@/lib/student-month";
import { readAttendanceWindows, readStudentDirectory } from "@/lib/student-month-api";
import { MonthlyStudentDirectory } from "./monthly-student-directory";

export const metadata = { title: "Students · LUMA" };

export default async function StudentsPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const identity = await requirePortal("staff");
  const { month: requestedMonth, q, type, sort, status, after } = await searchParams;
  if (typeof status === "string" || typeof after === "string") redirect(studentListHref({
    status: typeof status === "string" ? status : undefined, after: typeof after === "string" ? after : undefined,
    q: typeof q === "string" ? q : undefined,
  }));
  const now = renderTime();
  const today = academyToday(new Date(now));
  const { first, through, month } = classMonthRange(requestedMonth, today);
  const directoryRequest = readStudentDirectory();
  const dashboardRequest = through ? readAttendanceWindows(first, through, (from, to) => staffApi<Dashboard>(
    `/api/v1/attendance/dashboard?${new URLSearchParams({ from, to })}`, undefined, 30000), data => data.truncated) : null;

  return <StaffShell active="students" crumb="People" title="Students" subtitle={monthLabel(month)}
    hasStudentPortal={hasPortal(identity, "student")}>
    <ClassMonthPicker href="/staff/students" month={month} currentMonth={today.startsWith(month)} label="Attendance month" />
    <Suspense key={month} fallback={<StudentMonthSkeleton />}>
      <StudentMonthPanel month={month} now={now} q={q} type={type} sort={sort}
        directoryRequest={directoryRequest} dashboardRequest={dashboardRequest} />
    </Suspense>
  </StaffShell>;
}

async function StudentMonthPanel({ month, now, q, type, sort, directoryRequest, dashboardRequest }: {
  month: string; now: number; q?: string | string[]; type?: string | string[]; sort?: string | string[];
  directoryRequest: ReturnType<typeof readStudentDirectory>;
  dashboardRequest: Promise<Dashboard[] | null> | null;
}) {
  const [directory, dashboards] = await Promise.all([directoryRequest, dashboardRequest ?? []]);
  const data = directory ? compileStudentMonth(directory, dashboards ?? [], now) : null;
  return data ? <MonthlyStudentDirectory key={JSON.stringify([month, q, type, sort])} {...data} month={month} attendanceFailed={!dashboards}
      initialQuery={typeof q === "string" ? q.slice(0, 100) : ""}
      initialType={data.classTypes.find(option => option.key === type)?.key ?? ""}
      initialSort={sort === "low" || sort === "high" ? sort : "name"} />
    : <p className="session-error" role="alert">The student directory couldn’t load. <PanelRetry /></p>;
}
