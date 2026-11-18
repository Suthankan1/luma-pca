import Link from "next/link";
import { Shell } from "@/components/Shell";
import { AttendanceOverview } from "@/components/insights/AttendanceOverview";
import { RangeFilter } from "@/components/insights/RangeFilter";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, presets, withParams, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { academyToday } from "@/lib/staff-forms";

export const metadata = { title: "My attendance · LUMA" };

/** The student's own attendance: the API takes the student from the sign-in, so nobody else's can be shown here. */
export default async function MyAttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const query = await searchParams;
  const today = academyToday();
  const fallback = presets(today)[1];
  let from = dateOrNull(query.from) ?? fallback.from;
  let to = dateOrNull(query.to) ?? fallback.to;
  if (from > to) [from, to] = [to, from];
  const result = await studentApi<StudentOverview>(withParams("/api/v1/me/attendance-overview", { from, to }) as `/api/v1/me/${string}`,
    undefined, 30000);
  return <Shell active="student" studentSection="attendance" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <p className="link-row"><Link className="text-link" href="/student">← Student portal</Link></p>
    <h1 className="heading">My attendance</h1>
    {result.ok && <p className="sub"><strong>{result.data.fullName}</strong> · student ID <strong>{result.data.studentNumber}</strong></p>}
    <RangeFilter path="/student/attendance" from={from} to={to} today={today} />
    {result.ok ? <AttendanceOverview overview={result.data} self />
      : <p className="session-error" role="alert">We couldn’t load your attendance right now. Please reload the page shortly.</p>}
  </Shell>;
}
