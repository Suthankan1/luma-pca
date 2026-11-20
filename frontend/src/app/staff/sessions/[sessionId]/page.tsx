import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { LiveRefresh } from "@/components/LiveRefresh";
import { FinaliseForm } from "@/components/staff/AttendanceForms";
import { ClosePulseButton, PulseForm } from "@/components/staff/EngagementForms";
import { SessionOnlineTimeline } from "@/components/staff/SessionOnlineTimeline";
import { SessionAttendanceRoster } from "@/components/staff/SessionAttendanceRoster";
import { formatElapsed, formatTime, formatWebinarId as formatZoomId, formatWhen, runStart, type ClassRun } from "@/lib/webinars";
import { renderTime } from "@/lib/insights";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { compactClassTitle } from "@/lib/staff-home";
import {
  occurrencePeopleHref, occurrenceSource, formatClassTime, formatInstant, formatWebinarId, occurrenceState, uuidOrNull,
  reactionLabels, type ClassEngagement, type ClassSession, type Occurrence, type ProvisionalAttendance,
} from "@/lib/staff-forms";

export const metadata = { title: "Class · LUMA" };
const attendanceTimeoutMs = 25_000;

/** One class for staff: its students, Zoom run, attendance (with corrections) and engagement questions. */
export default async function SessionPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const identity = await requirePortal("staff");
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) notFound();
  const [session, zoom, attendance, engagement, zoomRun] = await Promise.all([
    staffApi<ClassSession>(`/api/v1/class-sessions/${sessionId}`),
    staffApi<Occurrence[]>(`/api/v1/zoom/occurrences?decided=true&sessionId=${sessionId}&limit=50`),
    staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${sessionId}/attendance?includeOnlineHistory=true`, undefined, attendanceTimeoutMs),
    staffApi<ClassEngagement>(`/api/v1/class-sessions/${sessionId}/engagement`),
    staffApi<ClassRun | null>(`/api/v1/class-sessions/${sessionId}/zoom-run`),
  ]);
  if (!session.ok && (session.status === 404 || session.status === 403)) notFound();
  const shell = { active: "sessions" as const, crumb: "Teaching / Classes", hasStudentPortal: hasPortal(identity, "student"),
    actions: <Link className="text-link" href="/staff/sessions">← Classes</Link> };
  if (!session.ok) {
    return <StaffShell {...shell} title="Class" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this class right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const s = session.data;
  const run = zoomRun.ok && zoomRun.data ? zoomRun.data : null;
  const now = renderTime();
  const cancelled = s.status !== "SCHEDULED";

  const runBegan = run ? runStart(run) : null;
  const facts = attendance.ok ? attendance.data : null;
  const students = facts?.students ?? [];
  const joined = students.filter(student => student.seen);
  const expected = students.filter(student => student.expected);
  const finalised = Boolean(facts?.finalisedAt);
  const credited = students.filter(student => student.outcome === "PRESENT" || student.outcome === "LATE").length;
  const running = run?.state === "LIVE";
  const legacy = s.origin !== "ZOOM_RUN";
  const actualEnd = run?.actualEndedAt;
  const sessionStart = Date.parse(runBegan?.at ?? s.startsAt);
  const sessionEnd = Date.parse(s.endsAt);
  const clock = facts ? Date.parse(facts.calculatedAt) : now;
  const runningWindow = !cancelled && (running || (legacy && sessionStart <= clock && clock < sessionEnd && !facts?.webinarEnded));
  const lastSample = facts?.onlineHistory?.at(-1);
  const showLastSample = lastSample && (facts?.webinarEnded || (!legacy && !run));
  const chartEnd = actualEnd ? Date.parse(actualEnd) : runningWindow ? clock
    : showLastSample ? Date.parse(lastSample.at) : sessionEnd;
  const duration = actualEnd ? Date.parse(actualEnd) : running ? clock : legacy ? sessionEnd : null;
  const meanConnected = joined.length ? Math.round(joined.reduce((sum, student) => sum + student.connectedSeconds, 0) / joined.length / 60) : null;
  const metrics = [
    { label: "Expected", value: facts ? expected.length : "—" },
    { label: "Students joined", value: facts ? joined.length : "—" },
    { label: "Expected not seen", value: facts && !cancelled && clock >= sessionStart && facts.linkedOccurrences > 0
      ? expected.filter(student => !student.seen).length : "—" },
    { label: "Attendance credited", value: finalised ? credited : "—" },
    { label: legacy ? "Scheduled duration" : "Class duration",
      value: !cancelled && duration != null ? formatElapsed((duration - sessionStart) / 1000) : "—" },
    { label: "Average time connected", value: meanConnected == null ? "—" : `${meanConnected} min` },
  ];
  const subtitle = run && runBegan
    ? `${running ? "Live now · " : ""}${formatWhen(runBegan.at, s.timeZone)} ${runBegan.known ? "actual start" : "first Zoom event"}${actualEnd ? `, ended ${formatTime(actualEnd, s.timeZone)}` : ""} (${s.timeZone})`
    : `${formatClassTime(s.startsAt, s.endsAt, s.timeZone)} (${s.timeZone})${cancelled ? " · Cancelled" : ""}`;
  return <StaffShell {...shell} title={compactClassTitle(s.title)} fullTitle={s.title} subtitle={subtitle}>
    <dl className="session-metrics" aria-label="Session summary">
      {metrics.map(metric => <div className="panel" key={metric.label}>
        <dt>{metric.label}</dt>
        <dd><strong>{metric.value}</strong></dd>
      </div>)}
    </dl>
    {facts && <SessionOnlineTimeline history={facts.onlineHistory ?? []} start={sessionStart} end={chartEnd}
      endLabel={actualEnd ? "End" : runningWindow ? "Now" : showLastSample ? "Last sample" : "Scheduled end"} timeZone={s.timeZone} />}
    <section className="panel panel-gap" aria-labelledby="attendance-title">
      <h2 id="attendance-title" className="panel-title">{!attendance.ok ? "ATTENDANCE" : finalised ? "FINAL ATTENDANCE"
        : attendance.data.status === "RECONCILED" ? "ATTENDANCE FROM ZOOM’S REPORT" : "PROVISIONAL ATTENDANCE"}</h2>
      {!attendance.ok ? <p className="session-error" role="alert">We couldn’t load attendance right now. Please reload shortly.</p> : <>
        {!finalised && <p className="panel-sub">{attendance.data.status === "RECONCILED"
          ? "From Zoom’s participant report, fetched after the webinar ended. No present or absent decision is made until finalisation."
          : "Provisional Zoom records. A missing join is not proof of absence."}</p>}
        {!attendance.data.finalisedAt && !cancelled && Date.parse(s.startsAt) - 15 * 60_000 <= Date.parse(attendance.data.calculatedAt)
          && Date.parse(attendance.data.calculatedAt) <= Date.parse(s.endsAt) + 30 * 60_000
          && <LiveRefresh until={new Date(Date.parse(s.endsAt) + 30 * 60_000).toISOString()} />}
        {!finalised && attendance.data.status === "RECONCILED" && !cancelled && sessionEnd <= clock
          && <FinaliseForm sessionId={s.id} />}
        <SessionAttendanceRoster key={s.id} students={students} sessionId={s.id} timeZone={s.timeZone} finalised={finalised} />
      </>}
    </section>
    <div className={run ? "staff-grid panel-gap" : "panel-gap"}>
      <section className="panel" aria-labelledby="session-details-title">
        <h2 id="session-details-title" className="panel-title">SESSION DETAILS</h2>
        <ul className="dir-list">{s.batches.map(b => <li key={b.batchId} className="dir-row dir-course">
          <div><strong>{b.code}</strong> <span>{b.name}</span></div>
          <Link className="text-link" href={`/staff/batches/${b.batchId}`}>Roster →</Link>
        </li>)}</ul>
        <p className="panel-sub">Zoom webinar: {s.zoomWebinarId ? formatWebinarId(s.zoomWebinarId) : "not set"}</p>
        <div aria-labelledby="zoom-links-title" role="region">
          <h3 id="zoom-links-title" className="form-title">Zoom occurrences linked</h3>
          {!zoom.ok ? <p className="session-error" role="alert">We couldn’t load the Zoom links right now.</p>
            : zoom.data.length === 0 ? <p className="panel-sub">No linked occurrences.</p>
            : <ul className="dir-list">{zoom.data.map(o => <li key={o.occurrenceUuid} className="dir-row">
              <span>Started {formatInstant(o.firstEventAt)} · {occurrenceSource(o)} · <Link className="text-link" href={occurrencePeopleHref(o)}>people</Link></span>
              <span className="status on">{occurrenceState(o)}</span>
            </li>)}</ul>}
          <p className="panel-sub"><Link className="text-link" href="/staff/zoom">Review Zoom occurrences →</Link></p>
        </div>
      </section>
      {run && runBegan ? <section className="panel" aria-labelledby="zoom-run-title">
        <h2 id="zoom-run-title" className="panel-title">ZOOM RUN</h2>
        <p className="panel-sub">This class was created when Zoom actually started webinar {formatZoomId(run.webinarId)}. Times come from Zoom;
          LUMA does not change them.</p>
        <dl className="zoom-run-facts">
          <div><dt>Status</dt><dd>{run.state === "LIVE" ? <span className="pill pill-live">Live</span> : "Ended"}</dd></div>
          <div><dt>Actual start</dt><dd>{formatTime(runBegan.at, s.timeZone)}{runBegan.known ? "" : " (first event; Zoom’s start not received)"}</dd></div>
          <div><dt>Actual end</dt><dd>{run.actualEndedAt ? formatTime(run.actualEndedAt, s.timeZone) : "—"}</dd></div>
          <div><dt>{run.state === "LIVE" ? "Running for" : "Length"}</dt>
            <dd>{formatElapsed(((run.actualEndedAt ? Date.parse(run.actualEndedAt) : now) - Date.parse(runBegan.at)) / 1000)}</dd></div>
          <div><dt>Zoom schedule</dt><dd>{run.scheduledStartAt ? `${formatWhen(run.scheduledStartAt, s.timeZone)}${run.scheduledDurationMinutes
            ? ` · ${run.scheduledDurationMinutes} min` : ""}` : "No Zoom schedule"}</dd></div>
          {run.state === "LIVE" && <div><dt>Connected now</dt><dd>{run.connectedNow}</dd></div>}
          <div><dt>Joined</dt><dd>{run.people} {run.people === 1 ? "person" : "people"}</dd></div>
          <div><dt>Zoom’s report</dt><dd>{run.reported ? "Received (final source)" : run.state === "LIVE" ? "After the webinar" : "Waiting for Zoom"}</dd></div>
          <div><dt>Academy class</dt><dd>{run.classResolved ? run.academyClassName : "Unresolved: no students expected"}</dd></div>
        </dl>
        <p className="link-row"><Link className="text-link" href="/staff/sessions">Tracked Zoom links →</Link></p>
      </section> : null}
    </div>
    {engagement.ok && engagement.data.enabled && <section className="panel panel-gap" aria-labelledby="engagement-title">
      <h2 id="engagement-title" className="panel-title">ENGAGEMENT</h2>
      <p className="panel-sub">From the student portal, consenting students only ({engagement.data.reactingStudents} reacted). Source: {engagement.data.source}.</p>
      <ul className="dir-list reaction-counts">{Object.entries(engagement.data.reactions).map(([kind, count]) =>
        <li key={kind} className="dir-row"><span>{reactionLabels[kind] ?? kind}</span><strong>{count}</strong></li>)}</ul>
      <h3 className="form-title">Questions</h3>
      <ul className="dir-list">{engagement.data.pulses.map(q => <li key={q.id} className="dir-course pulse" aria-label={`Question ${q.prompt}`}>
        <div className="dir-row"><strong>{q.prompt}</strong><span className={`status ${q.open ? "on" : "off"}`}>{q.open ? "Open" : "Closed"}</span></div>
        <ul className="dir-list">{q.options.map((o, i) => <li key={i} className="dir-row"><span>{o}</span><strong>{q.counts?.[i] ?? 0}</strong></li>)}</ul>
        {q.open && <ClosePulseButton sessionId={s.id} questionId={q.id} />}
      </li>)}
        {engagement.data.pulses.length === 0 && <li className="dir-empty muted">No questions yet.</li>}</ul>
      {!cancelled && <PulseForm sessionId={s.id} />}
    </section>}
  </StaffShell>;
}
