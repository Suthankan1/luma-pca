import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { AttendanceOverview } from "@/components/insights/AttendanceOverview";
import { RangeFilter } from "@/components/insights/RangeFilter";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, presets, withParams, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, isAcademyManager, uuidOrNull } from "@/lib/staff-forms";

export const metadata = { title: "Student attendance · LUMA" };

export default async function StudentAttendancePage({ params, searchParams }: {
  params: Promise<{ studentId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const identity = await requirePortal("staff");
  const studentId = uuidOrNull((await params).studentId);
  if (!studentId) notFound();
  const query = await searchParams;
  const today = academyToday();
  const fallback = presets(today)[1];
  let from = dateOrNull(query.from) ?? fallback.from;
  let to = dateOrNull(query.to) ?? fallback.to;
  if (from > to) [from, to] = [to, from];
  const base = { active: "students" as const, crumb: "People", hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...base} title="Student attendance" subtitle="">
      <p className="banner">Only academy owners and administrators open a student’s attendance. Use the dashboard for your batches.</p>
    </StaffShell>;
  }
  const result = await staffApi<StudentOverview>(withParams(`/api/v1/students/${studentId}/attendance-overview`, { from, to }) as `/api/v1/${string}`,
    undefined, 30000);
  if (!result.ok && result.status === 404) notFound();
  if (!result.ok) {
    return <StaffShell {...base} title="Student attendance" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this student’s attendance. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const o = result.data;
  return <StaffShell {...base} title={o.fullName} subtitle={`Student ID ${o.studentNumber} · attendance in every class, with each time they left and rejoined.`}>
    <p className="link-row"><Link className="text-link" href={withParams("/staff/dashboard", { from, to })}>← Dashboard</Link>
      {" · "}<Link className="text-link" href={`/staff/students/${studentId}`}>Student record →</Link></p>
    <RangeFilter path={`/staff/students/${studentId}/attendance`} from={from} to={to} today={today} />
    <AttendanceOverview overview={o} self={false} />
  </StaffShell>;
}
