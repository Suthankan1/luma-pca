import Link from "next/link";
import { Shell } from "@/components/Shell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { AppealForm } from "@/components/AppealForm";
import { ConsentForm } from "@/components/StudentEngagement";
import { classTimeLabel, formatClassTime, formatDate, classOpenForEngagement, classPhase, isLiveRun, formatDuration, outcomeLabels, sessionWindow, type AttendanceRecordView, type MyConsent } from "@/lib/staff-forms";
import { Kpis } from "@/components/insights/Kpis";
import { formatClock, formatDay, level, presets, renderTime, type StudentOverview } from "@/lib/insights";
import { academyToday } from "@/lib/staff-forms";

type MyEnrollment = { id: string; batchCode: string; batchName: string; enrolledOn: string; withdrawnOn: string | null; status: string };
type MyClass = { id: string; title: string; startsAt: string; endsAt: string; timeZone: string; localDate: string; status: string;
  batches: { batchId: string; code: string; name: string }[]; joinUrl?: string | null; origin?: string | null };

/** "in 2 days", "in 3 h", "in 25 min" until a class starts. */
function startsIn(startsAt: string, now = Date.now()) {
  const minutes = Math.max(0, Math.round((Date.parse(startsAt) - now) / 60_000));
  if (minutes < 60) return `in ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `in ${hours} h`;
  const days = Math.round(hours / 24);
  return `in ${days} day${days === 1 ? "" : "s"}`;
}

const WINDOW_DAYS = 28;

export const metadata = { title: "Student portal · LUMA" };

export default async function StudentPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("student");
  const params = await searchParams;
  const window = sessionWindow(params.from, undefined, WINDOW_DAYS);
  const href = (from?: string) => from ? `/student?from=${from}` : "/student";
  const query = new URLSearchParams({ from: window.fromInstant, to: window.toInstant });
  const [me, enrollments, classes, attendance, consent, overview] = await Promise.all([
    studentApi<{ studentNumber: string; fullName: string }>("/api/v1/me/student"),
    studentApi<MyEnrollment[]>("/api/v1/me/enrollments"),
    studentApi<MyClass[]>(`/api/v1/me/class-sessions?${query}`),
    studentApi<AttendanceRecordView[]>("/api/v1/me/attendance"),
    studentApi<MyConsent>("/api/v1/me/engagement-consent"),
    // The last 30 days, for the snapshot at the top; the full view is under My attendance.
    studentApi<StudentOverview>(`/api/v1/me/attendance-overview?from=${presets(academyToday())[1].from}&to=${academyToday()}`, undefined, 20000),
  ]);
  const now = renderTime();
  const scheduled = classes.ok ? classes.data.filter(c => c.status === "SCHEDULED" && classPhase(c.startsAt, c.endsAt, now) !== "over") : [];
  const next = scheduled[0];
  const nextLive = next ? classPhase(next.startsAt, next.endsAt, now) === "live" : false;
  const canTakePart = consent.ok && consent.data.enabled && consent.data.granted;

  return <Shell active="student" studentSection="home" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <header className="page-head">
      <div>
        <p className="crumbs">Physics Cube Academy</p>
        <h1 className="heading staff-heading">Student portal</h1>
        <p className="sub page-sub">{me.ok
          ? <>Hello, <strong>{me.data.fullName}</strong> · student ID <strong>{me.data.studentNumber}</strong></>
          : "You’re signed in to Physics Cube Academy."}</p>
      </div>
      <div className="page-actions"><Link className="action secondary" href="/student/attendance">My attendance →</Link></div>
    </header>

    {classes.ok && <section className={`next-class ${nextLive ? "is-live" : ""}`} aria-labelledby="next-class-title">
      <h2 id="next-class-title" className="next-class-label">{nextLive ? "Live now" : "Next class"}</h2>
      {next ? <div className="next-class-body">
        <div>
          <p className="next-class-title">{next.title}</p>
          <p className="next-class-when">{formatDay(next.startsAt, next.timeZone)} · {isLiveRun(next, now)
            ? `started ${formatClock(next.startsAt, next.timeZone)}` : `${formatClock(next.startsAt, next.timeZone)}–${formatClock(next.endsAt, next.timeZone)}`}
            {!nextLive && <span> · {startsIn(next.startsAt, now)}</span>}
            {next.batches.length > 0 && <span> · {next.batches.map(b => b.code).join(", ")}</span>}</p>
        </div>
        <div className="next-class-actions">
          {next.joinUrl && <a className="action join" href={next.joinUrl} target="_blank" rel="noopener noreferrer">
            {nextLive ? "Join on Zoom now" : "Zoom link"}<span className="sr-only"> (opens Zoom)</span></a>}
          {nextLive && canTakePart && <Link className="action secondary" href={`/student/classes/${next.id}`}>React and answer</Link>}
        </div>
      </div> : <p className="next-class-when">No upcoming classes in the next four weeks.</p>}
    </section>}

    {overview.ok && overview.data.overall.classes > 0 && <Kpis label="Last 30 days" items={[
      { label: "Attendance", value: `${overview.data.overall.percent}%`,
        note: overview.data.minPercent ? `the academy’s rule is ${overview.data.minPercent}%` : "share of class time",
        tone: level(overview.data.overall.percent, overview.data.minPercent) as "good" | "warn" | "bad" },
      { label: "Classes joined", value: `${overview.data.overall.joined} / ${overview.data.overall.classes}`, note: "last 30 days" },
      { label: "Time in classes", value: formatDuration(overview.data.overall.connectedSeconds), note: "last 30 days" },
    ]} />}

    {!enrollments.ok || !classes.ok
      ? <p className="session-error" role="alert">We couldn’t load your classes right now. Please reload the page shortly.</p>
      : <><div className="staff-grid">
        <section className="panel" aria-labelledby="my-classes-title">
          <h2 id="my-classes-title" className="panel-title">MY CLASSES</h2>
          <p className="panel-sub">{formatDate(window.from)} – {formatDate(window.lastDay)} · times in each class’s own time zone</p>
          <nav className="pager" aria-label="Four weeks">
            <Link className="text-link" href={href(window.previous)}>← Previous four weeks</Link>
            <Link className="text-link" href={href()}>From today</Link>
            <Link className="text-link" href={href(window.next)}>Next four weeks →</Link>
          </nav>
          <ul className="dir-list">
            {classes.data.map(c => <li key={c.id} className="dir-row dir-course" aria-label={c.title}>
              <div>
                <strong>{c.title}</strong>
                <span className="dir-dates muted"> {classTimeLabel(c, now)} ({c.timeZone})</span>
                <div className="dir-dates muted">{c.batches.map(batch => batch.code).join(", ")}</div>
              </div>
              <span className="dir-meta">
                {c.status === "SCHEDULED" && consent.ok && consent.data.enabled && consent.data.granted && classOpenForEngagement(c.startsAt, c.endsAt)
                  ? <Link className="text-link" href={`/student/classes/${c.id}`}>Take part</Link> : null}
                {c.joinUrl && classPhase(c.startsAt, c.endsAt, now) !== "over" &&
                  <a className="text-link" href={c.joinUrl} target="_blank" rel="noopener noreferrer">Zoom link<span className="sr-only"> for {c.title}</span></a>}
                {c.status === "SCHEDULED" && classPhase(c.startsAt, c.endsAt, now) === "live" ? <span className="pill pill-live">Live now</span>
                  : <span className={`status ${c.status === "SCHEDULED" ? "on" : "off"}`}>{c.status === "SCHEDULED"
                    ? classPhase(c.startsAt, c.endsAt, now) === "over" ? "Ended" : "Scheduled" : "Cancelled"}</span>}
              </span>
            </li>)}
            {classes.data.length === 0 && <li className="dir-empty muted">No classes in these four weeks.</li>}
          </ul>
        </section>
        <div className="side-stack">
        <section className="panel" aria-labelledby="my-batches-title">
          <h2 id="my-batches-title" className="panel-title">MY BATCHES</h2>
          <ul className="dir-list">
            {enrollments.data.map(e => <li key={e.id} className="dir-row dir-course">
              <div>
                <strong>{e.batchCode}</strong> {e.batchName}
                <div className="dir-dates muted">from {formatDate(e.enrolledOn)}{e.withdrawnOn ? `, withdrawn ${formatDate(e.withdrawnOn)}` : ""}</div>
              </div>
              <span className={`status ${e.withdrawnOn ? "off" : "on"}`}>{e.withdrawnOn ? "Withdrawn" : "Enrolled"}</span>
            </li>)}
            {enrollments.data.length === 0 && <li className="dir-empty muted">You aren’t enrolled in a batch yet. The academy office can enrol you.</li>}
          </ul>
        </section>
        {consent.ok && consent.data.enabled && <section className="panel" aria-labelledby="engagement-title">
          <h2 id="engagement-title" className="panel-title">REACTIONS AND QUESTIONS</h2>
          <p className="panel-sub">{consent.data.granted ? "You take part: during a class, open it below to react or answer questions."
            : consent.data.granted === false ? "You chose not to take part. Nothing is recorded about you." : "The academy asks whether you want to take part."}</p>
          <ConsentForm consent={consent.data} />
        </section>}
        </div></div>
        <section className="panel panel-gap" aria-labelledby="my-attendance-title">
          <h2 id="my-attendance-title" className="panel-title">MY ATTENDANCE</h2>
          <p className="panel-sub">Final attendance for your classes, decided by the academy’s rule from Zoom’s report. If something looks
            wrong, send an appeal and the academy will review it.</p>
          {!attendance.ok ? <p className="session-error" role="alert">We couldn’t load your attendance right now.</p>
            : <ul className="dir-list">
              {attendance.data.map((r, index) => <li key={r.id} className="dir-course my-attendance" aria-label={`Attendance ${r.classTitle}`}>
                <div className="dir-row">
                  <div><strong>{r.classTitle}</strong>
                    <span className="dir-dates muted"> {formatClassTime(r.classStartsAt, r.classEndsAt, r.timeZone)}</span>
                    <div className="dir-dates muted">{formatDuration(r.connectedSeconds)} connected
                      {r.appealStatus === "OPEN" ? " · appeal waiting for a decision" : r.appealStatus ? ` · appeal ${r.appealStatus.toLowerCase()}` : ""}</div></div>
                  <span className={`status ${r.outcome === "ABSENT" ? "off" : "on"}`}>{outcomeLabels[r.outcome]}</span>
                </div>
                {r.appealStatus !== "OPEN" && <details className="decision"><summary>Appeal this</summary>
                  <AppealForm prefix={`appeal-${index}`} recordId={r.id} /></details>}
              </li>)}
              {attendance.data.length === 0 && <li className="dir-empty muted">No final attendance yet.</li>}
            </ul>}
        </section>
      </>}
  </Shell>;
}
