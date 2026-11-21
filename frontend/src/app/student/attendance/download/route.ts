import { currentSession } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { academyToday } from "@/lib/staff-forms";
import { classMonthRange, isMonthKey, monthLabel } from "@/lib/staff-home";
import { readMyReportMonth } from "@/lib/student-month-api";
import { studentReportSessions } from "@/lib/student-month";
import { buildStudentReportPdf } from "@/lib/student-report-pdf";
import { studentApi } from "@/lib/staff-api";

export const runtime = "nodejs";
const headers = { "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff" };
const problem = (message: string, status: number) => new Response(message, { status, headers });

export async function GET(request: Request) {
  const { identity } = await currentSession();
  if (!identity) return problem("Sign in to download your attendance report.", 401);
  if (!hasPortal(identity, "student")) return problem("Student access is required.", 403);

  const month = new URL(request.url).searchParams.get("month");
  const now = new Date();
  const range = classMonthRange(month, academyToday(now));
  if (!isMonthKey(month) || range.month !== month) return problem("Select a valid month.", 400);
  const [reports, profile] = await Promise.all([
    readMyReportMonth(range),
    studentApi<{ fullName: string; studentNumber: string }>("/api/v1/me/student"),
  ]);
  if (!reports?.length || !profile.ok) return problem("Your attendance report couldn’t load. Please try again.", 503);

  const { fullName, studentNumber } = profile.data;
  const pdf = await buildStudentReportPdf({
    name: fullName, studentNumber, month: monthLabel(month), first: range.first, last: range.last, through: range.through,
    generatedAt: now.toISOString(), classes: studentReportSessions(reports, range.first, range.through, now.getTime()),
  });
  return new Response(pdf, { headers: {
    ...headers,
    "Content-Type": "application/pdf",
    "Content-Disposition": `attachment; filename="LUMA-attendance-${month}.pdf"`,
  } });
}
