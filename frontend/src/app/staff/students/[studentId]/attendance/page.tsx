import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { StudentAttendanceProfile } from "@/components/StudentAttendanceProfile";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, renderTime, withParams, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, uuidOrNull } from "@/lib/staff-forms";
import { classMonthRange, monthLabel } from "@/lib/staff-home";
import type { StudentDirectoryEntry } from "@/lib/student-month";
import { readAttendanceWindows } from "@/lib/student-month-api";

export const metadata = { title: "Student attendance · LUMA" };

export default async function StudentAttendancePage({ params, searchParams }: {
  params: Promise<{ studentId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const identity = await requirePortal("staff");
  const studentId = uuidOrNull((await params).studentId);
  if (!studentId) notFound();
  const query = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange(query.month, today, dateOrNull(query.from));
  const studentRequest = staffApi<StudentDirectoryEntry>(`/api/v1/students/${studentId}`);
  const reports = readAttendanceWindows(range.first, range.through ?? range.last, (from, to) => staffApi<StudentOverview>(
    `/api/v1/students/${studentId}/attendance-overview?${new URLSearchParams({ from, to })}`, undefined, 30000),
  data => data.classes.length >= 80);
  const student = await studentRequest;
  if (!student.ok && student.status === 404) notFound();
  const back = withParams("/staff/students", { month: range.month, q: typeof query.q === "string" ? query.q : null,
    type: typeof query.type === "string" ? query.type : null, sort: typeof query.sort === "string" ? query.sort : null });
  return <StaffShell active="students" crumb="Students / Attendance" title={student.ok ? student.data.fullName : "Student attendance"}
    subtitle={student.ok ? `${student.data.studentNumber} · ${monthLabel(range.month)}` : monthLabel(range.month)}
    hasStudentPortal={hasPortal(identity, "student")} actions={<Link href={back} className="text-link">← Back to Students</Link>}>
    <StudentAttendanceProfile range={range} today={today} now={now} reports={student.ok ? reports : null}
      href={`/staff/students/${studentId}/attendance`} staff />
  </StaffShell>;
}
