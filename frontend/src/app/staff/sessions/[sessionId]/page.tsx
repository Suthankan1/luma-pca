import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { LiveRefresh } from "@/components/LiveRefresh";
import { CorrectionForm, FinaliseForm, RecalculateForm } from "@/components/staff/AttendanceForms";
import { ClosePulseButton, PulseForm } from "@/components/staff/EngagementForms";
import { SessionOnlineTimeline } from "@/components/staff/SessionOnlineTimeline";
import { formatElapsed, formatTime, formatWebinarId as formatZoomId, formatWhen, runStart, type ClassRun } from "@/lib/webinars";
import { renderTime } from "@/lib/insights";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { compactClassTitle } from "@/lib/staff-home";
import {
  occurrencePeopleHref, occurrenceSource, formatClassTime, formatDate, formatDuration, formatInstant, formatWebinarId, instantParts, occurrenceState, uuidOrNull,
  outcomeLabels, reactionLabels, type ClassEngagement, type ClassSession, type Occurrence, type ProvisionalAttendance,
} from "@/lib/staff-forms";

type ExpectedStudent = { studentId: string; studentNumber: string; fullName: string; batchIds: string[] };

export const metadata = { title: "Class · LUMA" };

/** One class for staff: its students, Zoom run, attendance (with corrections) and engagement questions. */
export default async function SessionPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const identity = await requirePortal("staff");
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) notFound();
  const [session, expected, zoom, attendance, engagement, zoomRun] = await Promise.all([
    staffApi<ClassSession>(`/api/v1/class-sessions/${sessionId}`),
    staffApi<ExpectedStudent[]>(`/api/v1/class-sessions/${sessionId}/students`),
    staffApi<Occurrence[]>(`/api/v1/zoom/occurrences?decided=true&sessionId=${sessionId}&limit=50`),
    staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${sessionId}/attendance?includeOnlineHistory=true`),
    staffApi<ClassEngagement>(`/api/v1/class-sessions/${sessionId}/engagement`),
    staffApi<ClassRun | null>(`/api/v1/class-sessions/${sessionId}/zoom-run`),
  ]);
  if (!session.ok && (session.status === 404 || session.status === 403)) notFound();
  const shell = { active: "sessions" as const, crumb: "Teaching / Classes", hasStudentPortal: hasPortal(identity, "student"),
    actions: <Link className="text-link" href="/staff/sessions">← Classes</Link> };
  if (!session.ok || !expected.ok) {
    return <StaffShell {...shell} title="Class" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this class right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const s = session.data;
  const start = instantParts(s.startsAt, s.timeZone);
  const run = zoomRun.ok && zoomRun.data ? zoomRun.data : null;
  const now = renderTime();
  const batchCode = new Map(s.batches.map(b => [b.batchId, b.code]));
  const cancelled = s.status !== "SCHEDULED";

  const runBegan = run ? runStart(run) : null;
  const facts = attendance.ok ? attendance.data : null;
  const students = facts?.students ?? [];
  const joined = students.filter(student => student.seen);
  const expectedJoined = joined.filter(student => student.expected).length;
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
    { label: "Expected", value: expected.data.length, note: "Enrolled for this session" },
    { label: "Students joined", value: facts ? joined.length : "—", note: facts ? `${expectedJoined} from the expected roster` : "Matched Zoom participants" },
    { label: "Expected not seen", value: facts && !cancelled && clock >= sessionStart && facts.linkedOccurrences > 0
      ? students.filter(student => student.expected && !student.seen).length : "—", note: "No recorded Zoom join" },
    { label: "Attendance credited", value: facts?.finalisedAt ? credited : "—", note: facts?.finalisedAt ? "Present or late" : "Awaiting final attendance" },
    { label: "Class duration", value: !cancelled && duration != null ? formatElapsed((duration - sessionStart) / 1000) : "—",
      note: cancelled ? "Cancelled" : running ? "Elapsed so far" : legacy ? "Scheduled duration" : !actualEnd ? "End time unavailable"
        : runBegan?.known ? "Actual Zoom duration" : "From the first Zoom event" },
    { label: "Average time connected", value: meanConnected == null ? "—" : `${meanConnected} min`, note: "Across students who joined" },
  ];
  const subtitle = run && runBegan
    ? `${running ? "Live now · " : ""}${formatWhen(runBegan.at, s.timeZone)} ${runBegan.known ? "actual start" : "first Zoom event"}${actualEnd ? `, ended ${formatTime(actualEnd, s.timeZone)}` : ""} (${s.timeZone})`
    : `${formatClassTime(s.startsAt, s.endsAt, s.timeZone)} (${s.timeZone})${cancelled ? " · Cancelled" : ""}`;
  return <StaffShell {...shell} title={compactClassTitle(s.title)} fullTitle={s.title} subtitle={subtitle}>
    {cancelled && <p className="banner">This class is cancelled. It stays on record and is not linked to Zoom events automatically.</p>}
    <dl className="session-metrics" aria-label="Session summary">
      {metrics.map(metric => <div className="panel" key={metric.label}>
        <dt>{metric.label}</dt>
        <dd><strong>{metric.value}</strong><span>{metric.note}</span></dd>
      </div>)}
    </dl>
    {facts && <SessionOnlineTimeline history={facts.onlineHistory ?? []} start={sessionStart} end={chartEnd}
      endLabel={actualEnd ? "End" : runningWindow ? "Now" : showLastSample ? "Last sample" : "Scheduled end"} timeZone={s.timeZone} />}
    <section className="panel panel-gap" aria-labelledby="attendance-title">
      <h2 id="attendance-title" className="panel-title">{attendance.ok && attendance.data.finalisedAt ? "FINAL ATTENDANCE"
        : attendance.ok && attendance.data.status === "RECONCILED" ? "ATTENDANCE FROM ZOOM’S REPORT" : "PROVISIONAL ATTENDANCE"}</h2>
      {!attendance.ok ? <p className="session-error" role="alert">We couldn’t load attendance right now. Please reload shortly.</p> : <>
        <p className="panel-sub">{attendance.data.status === "RECONCILED"
            ? "From Zoom’s participant report, fetched after the webinar ended"
            : attendance.data.reportedOccurrences > 0 ? "Partly from Zoom’s participant report, partly from live Zoom events"
            : "From live Zoom events"}, recalculated each time this page loads ({formatInstant(attendance.data.calculatedAt)}).
          {" "}Time connected counts rejoins once and only within the class time.
          {" "}{attendance.data.finalisedAt ? "Final outcomes are shown below."
            : attendance.data.status === "RECONCILED" ? "No present or absent decision is made until attendance is finalised."
            : "This is not a final record: live events can arrive late or not at all, and a student not seen here is not proof of absence."}
          {" "}{attendance.data.linkedOccurrences === 0 ? "No Zoom occurrence is linked to this class yet."
            : attendance.data.webinarEnded ? "The webinar has ended." : "The webinar has not reported an end yet."}
          {attendance.data.unmatchedPeople > 0 && <> {attendance.data.unmatchedPeople} Zoom participant{attendance.data.unmatchedPeople === 1 ? " is" : "s are"} not
            matched to a student; <Link className="text-link" href="/staff/zoom">match them in Zoom review</Link>.</>}</p>
        {!attendance.data.finalisedAt && !cancelled && Date.parse(s.startsAt) - 15 * 60_000 <= Date.parse(attendance.data.calculatedAt)
          && Date.parse(attendance.data.calculatedAt) <= Date.parse(s.endsAt) + 30 * 60_000
          && <LiveRefresh until={new Date(Date.parse(s.endsAt) + 30 * 60_000).toISOString()} />}
        {attendance.data.finalisedAt
          ? <><p className="banner">Final since {formatInstant(attendance.data.finalisedAt)}, by the academy’s <Link className="text-link" href="/staff/attendance">attendance rule</Link>.
            Change a student’s outcome with a correction.</p>
            <RecalculateForm sessionId={s.id} /></>
          : attendance.data.status === "RECONCILED" && !cancelled && Date.parse(s.endsAt) <= Date.parse(attendance.data.calculatedAt)
            ? <FinaliseForm sessionId={s.id} /> : null}
        <p className="link-row"><a className="text-link" href={`/staff/sessions/${s.id}/attendance-export`} download>Download CSV</a>
          <span className="muted">Personal data; keep it private. Exports are recorded in the activity log.</span></p>
        <div className="session-roster-head" aria-hidden="true"><span>Student</span><span>Time connected</span><span>Attendance</span></div>
        <div className="session-roster-scroll" role="region" aria-label="Student attendance results" tabIndex={0}>
          <ul className="session-roster">
            {attendance.data.students.map(a => <li key={a.studentId} className="session-roster-row"
              aria-label={`Attendance ${a.studentNumber} ${a.fullName}`}>
              <div className="session-roster-person">
                <Link href={`/staff/students/${a.studentId}`} className="text-link">{a.fullName}</Link>
                <small>{a.studentNumber}{!a.expected && " · not expected at this class"}</small>
              </div>
              <div className="session-roster-time">{a.seen
                ? <><strong>{a.connectedSeconds > 0 && a.connectedSeconds < 60 ? `${a.connectedSeconds} sec` : formatDuration(a.connectedSeconds)} connected</strong>
                    <small>{a.firstJoinAt && <>first joined {formatInstant(a.firstJoinAt, s.timeZone)}
                      {a.secondsAfterStart ? ` (${formatDuration(a.secondsAfterStart)} after the start)` : ""}
                      {" · "}</>}{a.visits} visit{a.visits === 1 ? "" : "s"}{a.openVisits > 0 ? " · no leave received yet" : ""}</small></>
                : <span className="muted">No recorded join</span>}
              </div>
              <div className="session-roster-status">
                {a.outcome ? <span className={`status outcome ${a.outcome === "ABSENT" ? "off" : a.outcome === "EXCUSED" ? "" : "on"}`}>{outcomeLabels[a.outcome]}</span>
                  : <span className="status">{a.seen ? "Seen" : "Not seen"}</span>}
                {!a.outcome && <small>{attendance.data.finalisedAt ? "No final record" : "Outcome pending"}</small>}
              </div>
              {a.outcome && a.recordId && a.recordVersion != null && <details className="decision correction">
                <summary>Correct</summary>
                <CorrectionForm prefix={`correct-${a.studentId}`} sessionId={s.id} recordId={a.recordId} version={a.recordVersion} current={a.outcome} />
              </details>}
            </li>)}
            {attendance.data.students.length === 0 && <li className="dir-empty muted">No students are expected at this class.</li>}
          </ul>
        </div>
      </>}
    </section>
    <div className="staff-grid panel-gap">
      <section className="panel" aria-labelledby="expected-title">
        <h2 id="expected-title" className="panel-title">EXPECTED STUDENTS</h2>
        <p className="panel-sub">Enrolled in {s.batches.length === 1 ? "the batch" : "one of the batches"} on {formatDate(start.date)}; each student once.
          {" "}{expected.data.length} student{expected.data.length === 1 ? "" : "s"}.</p>
        <ul className="dir-list">
          {expected.data.map(student => <li key={student.studentId} className="dir-row dir-course">
            <div><Link className="text-link" href={`/staff/students/${student.studentId}`}><strong>{student.studentNumber}</strong> <span>{student.fullName}</span></Link></div>
            <span className="dir-dates muted">{student.batchIds.map(id => batchCode.get(id)).filter(Boolean).join(", ")}</span>
          </li>)}
          {expected.data.length === 0 && <li className="dir-empty muted">No students are enrolled in these batches on that date.</li>}
        </ul>
        <h3 className="form-title panel-gap">Batches</h3>
        <ul className="dir-list">{s.batches.map(b => <li key={b.batchId} className="dir-row dir-course">
          <div><strong>{b.code}</strong> <span>{b.name}</span></div>
          <Link className="text-link" href={`/staff/batches/${b.batchId}`}>Roster →</Link>
        </li>)}</ul>
        <p className="panel-sub">Zoom webinar: {s.zoomWebinarId ? formatWebinarId(s.zoomWebinarId) : "not set"}</p>
        <div aria-labelledby="zoom-links-title" role="region">
          <h3 id="zoom-links-title" className="form-title">Zoom occurrences linked</h3>
          {!zoom.ok ? <p className="session-error" role="alert">We couldn’t load the Zoom links right now.</p>
            : zoom.data.length === 0 ? <p className="panel-sub">None yet. Links are made when Zoom reports this webinar running around the class time,
              and checked again whenever the class is changed.</p>
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
      </section>
      : <section className="panel" aria-labelledby="zoom-run-title">
        <h2 id="zoom-run-title" className="panel-title">ZOOM</h2>
        <p className="panel-sub">This class was scheduled in LUMA before Zoom owned schedules. It stays on record with its attendance; it can no
          longer be edited or cancelled here.</p>
      </section>}
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
