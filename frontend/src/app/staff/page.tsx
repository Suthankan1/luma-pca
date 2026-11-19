import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { classPhase, classTimeLabel, isAcademyManager, sessionWindow, type AttendanceAppeal, type ClassSession, type PendingAttendance } from "@/lib/staff-forms";
import { renderTime } from "@/lib/insights";
import { type WebinarMonitor } from "@/lib/webinars";

export default async function StaffPage() {
  const identity = await requirePortal("staff");
  const manager = isAcademyManager(identity.roles);
  const analystOnly = !manager && identity.roles.includes("ANALYST")
    && !identity.roles.some(role => role === "INSTRUCTOR" || role === "ATTENDANCE_OFFICER");
  const { fromInstant, toInstant } = sessionWindow(null, undefined, 1);
  const [classes, pending, appeals, monitors] = await Promise.all([
    analystOnly ? null : staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: fromInstant, to: toInstant })}`),
    manager ? staffApi<PendingAttendance>("/api/v1/attendance/pending") : null,
    manager ? staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN") : null,
    manager ? staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors") : null,
  ]);
  const now = renderTime();
  const dateLabel = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Colombo", weekday: "long", day: "numeric", month: "long", year: "numeric",
  }).format(now);
  const schedule = classes?.ok ? classes.data.filter(item => item.status === "SCHEDULED")
    .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt)) : [];
  const live = monitors?.ok ? monitors.data.flatMap(webinar => webinar.live ? [{
    id: webinar.live.classSessionId,
    title: webinar.topic ?? "Zoom webinar",
    connected: webinar.live.connectedNow,
  }] : []) : [];
  const scheduled = schedule.filter(item => !live.some(run => run.id === item.id));
  const tasks = [
    { label: "Classes ready to finalise", count: pending?.ok ? pending.data.classes.filter(item => item.state === "READY").length : 0 },
    { label: "Appeals waiting for a decision", count: appeals?.ok ? appeals.data.length : 0 },
  ].filter(task => task.count > 0);

  return <StaffShell active="overview" crumb="" title="Staff portal"
    subtitle={dateLabel} hasStudentPortal={hasPortal(identity, "student")}>
    <div className="staff-home">
      <section className="home-brief" aria-labelledby="home-brief-title">
        <h2 id="home-brief-title">{analystOnly ? "Attendance by batch" : "Your teaching day"}</h2>
        <Link className="home-brief-link" href={analystOnly ? "/staff/attendance?section=totals" : "/staff/sessions"}>
          {analystOnly ? "Open reports" : "View all classes"} <span aria-hidden>↗</span>
        </Link>
      </section>

      {!analystOnly && <div className={manager ? "home-panels" : "home-panels home-panels-single"}>
        <section className="home-card" aria-labelledby="home-classes-title">
          <div className="home-card-heading"><h2 id="home-classes-title">Today’s classes</h2></div>
          {(live.length > 0 || scheduled.length > 0) ? <ul className="home-list">
            {live.map(run => <li key={run.id}>
              <Link href={`/staff/sessions/${run.id}`}>
                <span className="home-row-copy"><strong>{run.title}</strong><span>{run.connected} connected now</span></span>
                <span className="home-class-state home-class-live">Live</span><span aria-hidden>↗</span>
              </Link>
            </li>)}
            {scheduled.map(item => {
              const phase = classPhase(item.startsAt, item.endsAt, now);
              return <li key={item.id}>
                <Link href={`/staff/sessions/${item.id}`}>
                  <span className="home-row-copy"><strong>{item.title}</strong><span>{classTimeLabel(item, now)}</span></span>
                  <span className="home-class-state">{phase === "over" ? "Ended" : phase === "live" ? "In progress" : "Upcoming"}</span>
                  <span aria-hidden>↗</span>
                </Link>
              </li>;
            })}
          </ul> : null}
          {classes?.ok && !live.length && !scheduled.length && <p className="home-empty">No classes scheduled today.</p>}
          {!classes?.ok && <p className="home-empty">{classes?.status === 403 ? "Your role has no classes." : "Today’s schedule couldn’t load."}</p>}
          {monitors && !monitors.ok && <p className="home-empty">Live webinar status couldn’t load.</p>}
        </section>

        {manager && <section className="home-card" aria-labelledby="home-attention-title">
          <div className="home-card-heading"><h2 id="home-attention-title">Needs attention</h2></div>
          {tasks.length > 0 && <ul className="home-list">
            {tasks.map(task => <li key={task.label}>
              <Link href="/staff/attendance">
                <strong>{task.label}</strong><span className="home-task-count">{task.count}</span><span aria-hidden>↗</span>
              </Link>
            </li>)}
          </ul>}
          {(!pending?.ok || !appeals?.ok) && <p className="home-empty">Some attendance tasks couldn’t load.</p>}
          {pending?.ok && appeals?.ok && tasks.length === 0 && <p className="home-empty">No attendance tasks to review.</p>}
        </section>}
      </div>}
    </div>
  </StaffShell>;
}
