import Link from "next/link";
import { notFound } from "next/navigation";
import type { CSSProperties } from "react";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { formatDay, formatShortDay, renderTime, type Dashboard } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, academyToday, formatDate, type ClassSession, type ProvisionalAttendance } from "@/lib/staff-forms";
import { attendancePercent, averageClassAttendance, classMonthRange, monthLabel, monthlyClassTypes, timeRange, type ClassAttendanceTotal } from "@/lib/staff-home";
import { StudentAttendanceList } from "./student-attendance-list";

export const metadata = { title: "Class type attendance · LUMA" };

type Enrolment = { studentId: string; studentName: string; studentNumber: string; enrolledOn: string; withdrawnOn: string | null };
type SessionRate = { session: ClassSession; percent: number | null };
const timeout = 25000;

export default async function ClassTypePage({ searchParams }: { searchParams: Promise<{ type?: string | string[]; month?: string | string[] }> }) {
  const identity = await requirePortal("staff");
  const { type, month: requestedMonth } = await searchParams;
  if (typeof type !== "string" || !type) notFound();
  const now = renderTime();
  const today = academyToday(new Date(now));
  const { month, first, through, fromInstant, toInstant } = classMonthRange(requestedMonth, today);
  if (!through) notFound();
  const range = new URLSearchParams({ from: fromInstant, to: toInstant });
  const sessions = await staffApi<ClassSession[]>(`/api/v1/class-sessions?${range}`, undefined, timeout);
  const shell = { active: "sessions" as const, crumb: "Teaching / Classes", hasStudentPortal: hasPortal(identity, "student"),
    actions: <Link href={`/staff/sessions?month=${month}`} className="text-link">← Back to Classes</Link> };
  if (!sessions.ok) return <StaffShell {...shell} title="Class type attendance" subtitle={monthLabel(month)}>
    <p className="session-error">Class history couldn’t load. Please reload.</p>
  </StaffShell>;
  const group = monthlyClassTypes(sessions.data, now).find(([key]) => key === type)?.[1];
  if (!group) notFound();

  const batchIds = [...new Set(group.sessions.flatMap(session => session.batches.map(batch => batch.batchId)))];
  const attendanceRange = new URLSearchParams({ from: first, to: through });
  if (batchIds.length === 1) attendanceRange.set("batchId", batchIds[0]);
  const [dashboard, rosters] = await Promise.all([
    staffApi<Dashboard>(`/api/v1/attendance/dashboard?${attendanceRange}`, undefined, timeout),
    Promise.all(batchIds.map(id => staffApi<Enrolment[]>(`/api/v1/batches/${id}/enrollments`))),
  ]);
  const rosterComplete = rosters.every(result => result.ok);
  const enrolled = new Map(rosters.flatMap(result => result.ok ? result.data : [])
    .filter(item => item.enrolledOn <= through && (!item.withdrawnOn || item.withdrawnOn > through))
    .map(item => [item.studentId, item]));
  const ids = new Set(group.sessions.map(session => session.id));
  const totals = new Map<string, ClassAttendanceTotal>();
  const studentCounts = new Map<string, { expected: number; joined: number }>();
  function addStudent(studentId: string, joined: boolean) {
    if (!enrolled.has(studentId)) return;
    const counts = studentCounts.get(studentId) ?? { expected: 0, joined: 0 };
    counts.expected++;
    counts.joined += Number(joined);
    studentCounts.set(studentId, counts);
  }
  if (dashboard.ok) {
    const indexes: number[] = [];
    dashboard.data.classes.forEach((session, index) => {
      if (ids.has(session.id)) {
        if (session.joined > 0) indexes.push(index);
        totals.set(session.id, session);
      }
    });
    for (const student of dashboard.data.students) {
      for (const index of indexes) {
        const cell = student.cells[index];
        if (cell) addStudent(student.studentId, cell.visits > 0);
      }
    }
  }
  // The shared dashboard caps its class matrix. Fetch any omitted selected sessions in small batches.
  const omitted = dashboard.ok ? group.sessions.filter(session => !totals.has(session.id)) : [];
  for (let offset = 0; offset < omitted.length; offset += 6) {
    const results = await Promise.all(omitted.slice(offset, offset + 6)
      .map(session => staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${session.id}/attendance`, undefined, timeout)));
    for (const result of results) {
      if (!result.ok) continue;
      const expected = result.data.students.filter(student => student.expected);
      const joined = expected.filter(student => student.seen).length;
      totals.set(result.data.sessionId, { id: result.data.sessionId, expected: expected.length, joined });
      if (joined > 0) {
        for (const student of expected) addStudent(student.studentId, student.seen);
      }
    }
  }

  const complete = totals.size === ids.size;
  const counted = group.sessions.filter(session => (totals.get(session.id)?.joined ?? 0) > 0);
  const average = complete ? averageClassAttendance(counted, totals) : null;
  const rates = group.sessions.flatMap(session => {
    const total = totals.get(session.id);
    if (total?.joined === 0) return [];
    return [{ session, percent: total && total.expected > 0 ? total.joined * 100 / total.expected : null }];
  });
  const students = [...enrolled.values()].map(({ studentId, studentName, studentNumber }) => {
    const counts = studentCounts.get(studentId);
    return { studentId, studentName, studentNumber, percent: complete && counts ? attendancePercent(counts) : null };
  });

  return <StaffShell {...shell} title={group.title} subtitle={`${monthLabel(month)} · through ${formatDate(through)} · Sri Lanka time`}>
    <div className="class-detail-metrics">
      <section className="panel"><h2 className="panel-title">Enrolled students</h2><strong>{rosterComplete ? enrolled.size : "—"}</strong></section>
      <section className="panel"><h2 className="panel-title">Average attendance</h2><strong>{average === 0 ? "<1%" : showPercent(average)}</strong></section>
    </div>
    {!complete && <p className="session-error" role="status">Some attendance figures couldn’t load. Please reload to see the complete average.</p>}
    <Trend rates={rates} />
    <div className="class-detail-lists panel-gap">
      <section className="panel" aria-labelledby="enrolled-title">
        <h2 id="enrolled-title" className="panel-title">Enrolled students</h2>
        <p className="panel-sub">Share of this month’s expected sessions each student joined</p>
        {rosterComplete ? <StudentAttendanceList key={type} students={students} />
          : <p className="session-error">The full roster couldn’t load.</p>}
      </section>
      <section className="panel" aria-labelledby="held-title">
        <h2 id="held-title" className="panel-title">Completed sessions</h2>
        <p className="panel-sub">{rates.length} {rates.length === 1 ? "session" : "sessions"} this month</p>
        <div className="classes-panel-scroll" role="region" aria-label="Completed session attendance" tabIndex={0}>
          {rates.length === 0 ? <p className="dir-empty muted">No completed sessions with recorded joins this month.</p>
            : <ul>{rates.map(({ session, percent }) => <li key={session.id}>
            <Link className="class-detail-row" href={`/staff/sessions/${session.id}`}>
              <div><span>{formatDay(session.startsAt, ACADEMY_TIME_ZONE)}</span><small>{timeRange({ ...session, timeZone: ACADEMY_TIME_ZONE })}
                {session.origin !== "ZOOM_RUN" && " · Scheduled time"}</small></div>
              <strong className="class-attendance-percent">{showPercent(percent)}</strong>
            </Link>
          </li>)}</ul>}
        </div>
      </section>
    </div>
  </StaffShell>;
}

function showPercent(percent: number | null) {
  if (percent == null) return "—";
  return percent < 0.5 ? "<1%" : `${Math.round(percent)}%`;
}

function Trend({ rates }: { rates: SessionRate[] }) {
  const chronological = [...rates].reverse();
  const first = chronological[0]?.session;
  const last = chronological.at(-1)?.session;
  const since = first ? Date.parse(first.startsAt) : 0;
  const span = last ? Date.parse(last.startsAt) - since : 0;
  const points = chronological.map(({ session, percent }) => ({ session, percent,
    x: span > 0 ? (Date.parse(session.startsAt) - since) * 100 / span : 50,
  }));
  const line = points.reduce((segments, { x, percent }, index) => percent == null ? segments
    : `${segments} ${index > 0 && points[index - 1].percent != null ? "L" : "M"}${x},${100 - percent}`, "");
  return <figure className="panel panel-gap" aria-label="Monthly attendance trend">
    <figcaption className="panel-title">Monthly attendance trend</figcaption>
    {line && first && last ? <>
      <div className="class-trend-frame">
        <div className="class-trend-axis" aria-hidden="true"><span>100%</span><span>50%</span><span>0%</span></div>
        <div className="class-trend-plot">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            <path className="class-trend-grid" d="M0 0H100 M0 50H100 M0 100H100" vectorEffect="non-scaling-stroke" />
            <path className="class-trend-line" d={line} pathLength={1} vectorEffect="non-scaling-stroke" />
          </svg>
          {points.map(({ session, percent, x }) => percent == null ? null : <Link key={session.id}
            className="class-trend-point" href={`/staff/sessions/${session.id}`}
            style={{ "--x": `${x}%`, "--y": `${100 - percent}%` } as CSSProperties} data-edge={x > 70 ? "end" : undefined}
            aria-label={`${formatDay(session.startsAt, ACADEMY_TIME_ZONE)} at ${timeRange({ ...session, timeZone: ACADEMY_TIME_ZONE })}, ${showPercent(percent)} attendance`}>
            <span aria-hidden="true">{formatShortDay(session.startsAt, ACADEMY_TIME_ZONE)} · {timeRange({ ...session, timeZone: ACADEMY_TIME_ZONE })} · {showPercent(percent)}</span>
          </Link>)}
        </div>
      </div>
      <div className="class-trend-dates" aria-hidden="true" data-single={span === 0 || undefined}><span>{formatShortDay(first.startsAt, ACADEMY_TIME_ZONE)}</span>
        {span > 0 && <span>{formatShortDay(last.startsAt, ACADEMY_TIME_ZONE)}</span>}</div>
    </> : <p className="dir-empty muted">No attendance percentages to plot yet.</p>}
  </figure>;
}
