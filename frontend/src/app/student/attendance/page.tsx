import { Suspense } from "react";
import { StudentAttendanceProfile, type ProfileClass } from "@/components/StudentAttendanceProfile";
import { ConsentForm } from "@/components/StudentEngagement";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, renderTime } from "@/lib/insights";
import { studentApi, type ApiResult } from "@/lib/staff-api";
import { academyToday, type MyConsent } from "@/lib/staff-forms";
import { classMonthRange, monthLabel } from "@/lib/staff-home";
import { readMyReportMonth } from "@/lib/student-month-api";

export const metadata = { title: "My Report · LUMA" };

export default async function MyAttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  await requirePortal("student");
  const query = await searchParams;
  const now = renderTime();
  const today = academyToday(new Date(now));
  const range = classMonthRange(query.month, today, dateOrNull(query.from));
  const scheduleRange = new URLSearchParams({ from: range.fromInstant, to: range.toInstant });
  const schedule = studentApi<ProfileClass[]>(`/api/v1/me/class-sessions?${scheduleRange}`, undefined, 25000);
  const me = studentApi<StudentHeading>("/api/v1/me/student");
  const reports = readMyReportMonth(range);
  const consent = studentApi<MyConsent>("/api/v1/me/engagement-consent");
  return <div className="student-report-page">
    <header className="student-live-card student-section-hero student-portal-enter">
      <div>
        <h1>My Report</h1>
        <p><Suspense fallback={null}><ReportIdentity request={me} /></Suspense>{monthLabel(range.month)}</p>
      </div>
      <a className="student-live-calendar-link" href={`/student/attendance/download?month=${range.month}`} download>
        Download report <span aria-hidden="true">↓</span>
      </a>
    </header>
    <div className="student-portal-enter">
      <StudentAttendanceProfile range={range} today={today} now={now} reports={reports} schedule={schedule} href="/student" />
    </div>
    <Suspense fallback={null}><ReportEngagement request={consent} /></Suspense>
  </div>;
}

type StudentHeading = { fullName: string; studentNumber: string };

async function ReportIdentity({ request }: { request: Promise<ApiResult<StudentHeading>> }) {
  const me = await request;
  return me.ok ? <>{me.data.fullName} · {me.data.studentNumber} · </> : null;
}

async function ReportEngagement({ request }: { request: Promise<ApiResult<MyConsent>> }) {
  const consent = await request;
  return consent.ok && consent.data.enabled ? <section className="panel panel-gap" aria-labelledby="engagement-title">
    <h2 id="engagement-title" className="panel-title">Reactions and questions</h2>
    <ConsentForm consent={consent.data} />
  </section> : null;
}
