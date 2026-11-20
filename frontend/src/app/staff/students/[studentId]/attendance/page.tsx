import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { AttendanceOverview } from "@/components/insights/AttendanceOverview";
import { RangeFilter } from "@/components/insights/RangeFilter";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, formatDay, formatDuration, outcomeLabels, presets, renderTime, withParams, type ClassAttendance, type StudentOverview } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, uuidOrNull } from "@/lib/staff-forms";
import { classMonthRange, classTypeKey, compactClassTitle, monthLabel } from "@/lib/staff-home";
import { attendancePercentFor, completedForStudent, participationFor, type Participation, type StudentDirectoryEntry } from "@/lib/student-month";
import { readAttendanceWindows } from "@/lib/student-month-api";

export const metadata = { title: "Student attendance · LUMA" };

export default async function StudentAttendancePage({ params, searchParams }: {
  params: Promise<{ studentId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const identity = await requirePortal("staff");
  const studentId = uuidOrNull((await params).studentId);
  if (!studentId) notFound();
  const query = await searchParams;
  if (typeof query.month === "string") return <MonthlyStudentAttendance studentId={studentId} query={query}
    hasStudentPortal={hasPortal(identity, "student")} />;
  const today = academyToday();
  const fallback = presets(today)[1];
  let from = dateOrNull(query.from) ?? fallback.from;
  let to = dateOrNull(query.to) ?? fallback.to;
  if (from > to) [from, to] = [to, from];
  const base = { active: "students" as const, crumb: "People", hasStudentPortal: hasPortal(identity, "student") };
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

async function MonthlyStudentAttendance({ studentId, query, hasStudentPortal }: {
  studentId: string; query: Record<string, string | string[] | undefined>; hasStudentPortal: boolean;
}) {
  const now = renderTime();
  const { month, first, through } = classMonthRange(query.month, academyToday(new Date(now)));
  const [student, reports] = await Promise.all([
    staffApi<StudentDirectoryEntry>(`/api/v1/students/${studentId}`),
    through ? readAttendanceWindows(first, through, (from, to) => staffApi<StudentOverview>(
      `/api/v1/students/${studentId}/attendance-overview?${new URLSearchParams({ from, to })}`, undefined, 30000),
    data => data.classes.length >= 80) : [],
  ]);
  if (!student.ok && student.status === 404) notFound();
  const back = withParams("/staff/students", { month, q: typeof query.q === "string" ? query.q : null,
    type: typeof query.type === "string" ? query.type : null, sort: typeof query.sort === "string" ? query.sort : null });
  const groups = new Map<string, { title: string; sessions: ClassAttendance[]; counts: Participation }>();
  const totals = { expected: 0, joined: 0 };
  const completed = (reports ?? []).flatMap(report => report.classes).filter(session => completedForStudent(session, now))
    .sort((a, b) => b.startsAt.localeCompare(a.startsAt));
  for (const session of completed) {
    const key = classTypeKey(session.title);
    const group = groups.get(key) ?? { title: compactClassTitle(session.title), sessions: [], counts: { expected: 0, joined: 0 } };
    const contribution = participationFor(session, session);
    group.sessions.push(session);
    group.counts.expected += contribution.expected;
    group.counts.joined += contribution.joined;
    totals.expected += contribution.expected;
    totals.joined += contribution.joined;
    groups.set(key, group);
  }
  const formatRate = (counts: Participation) => {
    const rate = attendancePercentFor(counts);
    return rate == null ? "—" : `${Math.round(rate)}%`;
  };
  return <StaffShell active="students" crumb="Students" title={student.ok ? student.data.fullName : "Student attendance"}
    subtitle={student.ok ? `${student.data.studentNumber} · ${monthLabel(month)}` : monthLabel(month)}
    hasStudentPortal={hasStudentPortal} actions={<Link href={back} className="text-link">← Back to Students</Link>}>
    {!student.ok || !reports ? <p className="session-error" role="alert">This student’s monthly attendance couldn’t load.</p> : <>
      <div className="student-month-summary">
        <section className="panel"><h2 className="panel-title">Overall attendance</h2><strong>{formatRate(totals)}</strong></section>
        <section className="panel"><h2 className="panel-title">Tracked sessions joined</h2><strong>{totals.joined} / {totals.expected}</strong></section>
      </div>
      {groups.size === 0 && <section className="panel panel-gap"><p className="panel-sub">No completed sessions for this student in {monthLabel(month)}.</p></section>}
      {[...groups].map(([key, group]) => <section key={key} className="panel panel-gap student-month-type" aria-label={group.title}>
        <header><h2 className="panel-title">{group.title}</h2><strong className="class-attendance-percent">{formatRate(group.counts)}</strong></header>
        <div tabIndex={0} className="student-month-list" role="region" aria-label={`Sessions for ${group.title}`}>
          <ul className="dir-list">{group.sessions.map(session => {
            const counts = participationFor(session, session);
            const status = session.outcome ? outcomeLabels[session.outcome] ?? session.outcome
              : !counts.expected ? "Attendance unavailable" : counts.joined ? "Joined · pending" : "No recorded join · pending";
            return <li className="student-month-session" key={session.id}>
              <Link href={`/staff/sessions/${session.id}`} prefetch={false} className="text-link">
                {formatDay(session.startsAt, ACADEMY_TIME_ZONE)}
              </Link>
              <span>{counts.expected ? formatDuration(session.connectedSeconds) : "—"}</span>
              <span className="student-month-outcome" data-outcome={session.outcome ?? "pending"}>{status}</span>
            </li>;
          })}</ul>
        </div>
      </section>)}
    </>}
  </StaffShell>;
}
