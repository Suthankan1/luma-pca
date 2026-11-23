"use client";

import { useRouter } from "next/navigation";
import Link from "next/link";
import { useRef, useState, type ReactNode } from "react";
import { formatElapsed, formatTime, formatWebinarId, formatWhen, runStart, type ClassRun } from "@/lib/webinars";
import { reactionLabels, type ClassEngagement, type ClassSession, type ProvisionalAttendance } from "@/lib/staff-forms";
import type { LiveSessionSnapshot } from "@/lib/staff-live";
import { FinaliseForm } from "./AttendanceForms";
import { ClosePulseButton, PulseForm } from "./EngagementForms";
import { PanelRetry } from "./PanelRetry";
import { SessionAttendanceRoster } from "./SessionAttendanceRoster";
import { SessionOnlineTimeline } from "./SessionOnlineTimeline";
import { useLivePoll } from "./useLivePoll";

export function SessionAttendancePanel({ session, initialRun, initial, initialEngagement, now, children }: {
  session: ClassSession; initialRun: ClassRun | null; initial: ProvisionalAttendance | null;
  initialEngagement: ClassEngagement | null; now: number; children: ReactNode;
}) {
  const router = useRouter();
  const [latest, setLatest] = useState<LiveSessionSnapshot | null>(null);
  const [paused, setPaused] = useState(false);
  const endRefreshed = useRef(false);
  const current = latest && (!initial || Date.parse(latest.attendance.calculatedAt) > Date.parse(initial.calculatedAt)) ? latest : null;
  const facts = current?.attendance ?? initial;
  const run = current?.run === undefined ? initialRun : current.run;
  const engagement = current?.engagement ?? initialEngagement;
  const students = facts?.students ?? [];
  const joined = students.filter(student => student.seen);
  const expected = students.filter(student => student.expected);
  const finalised = Boolean(facts?.finalisedAt);
  const noAttendees = facts ? facts.status === "NO_ATTENDEES" : session.noAttendees;
  const credited = students.filter(student => student.outcome === "PRESENT" || student.outcome === "LATE").length;
  const running = run?.state === "LIVE" && !facts?.webinarEnded;
  const cancelled = session.status !== "SCHEDULED";
  const legacy = session.origin !== "ZOOM_RUN";
  const actualEnd = run?.actualEndedAt;
  const sessionStart = Date.parse((run ? runStart(run).at : null) ?? session.startsAt);
  const sessionEnd = Date.parse(facts?.classEndsAt ?? session.endsAt);
  const clock = facts ? Date.parse(facts.calculatedAt) : now;
  const runningWindow = !cancelled && (running || (legacy && sessionStart <= clock && clock < sessionEnd && !facts?.webinarEnded));
  const lastSample = facts?.onlineHistory?.at(-1);
  const showLastSample = lastSample && (facts?.webinarEnded || (!legacy && !run));
  const chartEnd = actualEnd ? Date.parse(actualEnd) : runningWindow ? clock : showLastSample ? Date.parse(lastSample.at) : sessionEnd;
  const duration = actualEnd ? Date.parse(actualEnd) : running ? clock : legacy ? sessionEnd : null;
  const until = sessionEnd + 30 * 60_000;
  const showPoll = !finalised && !noAttendees && !cancelled && sessionStart - 15 * 60_000 <= clock && clock <= until;
  const pollPath = `/staff/sessions/${session.id}/live${initialEngagement?.enabled ? "?engagement=true" : ""}`;
  const delayed = useLivePoll<LiveSessionSnapshot>(pollPath, showPoll && !paused, data => {
    setLatest(data);
    if (data.attendance.webinarEnded && initialRun?.state === "LIVE" && !endRefreshed.current) {
      endRefreshed.current = true;
      router.refresh();
    }
    return Boolean(data.attendance.finalisedAt) || data.attendance.status === "NO_ATTENDEES";
  }, until);

  const metrics = [
    { label: "Expected", value: facts ? expected.length : "—" },
    { label: "Students joined", value: facts ? joined.length : "—" },
    { label: "Expected not seen", value: facts && !cancelled && clock >= sessionStart && facts.linkedOccurrences > 0
      ? expected.filter(student => !student.seen).length : "—" },
    { label: "Attendance credited", value: finalised ? credited : "—" },
    { label: legacy ? "Scheduled duration" : "Class duration",
      value: !cancelled && duration != null ? formatElapsed((duration - sessionStart) / 1000) : "—" },
  ];

  return <>
    {noAttendees ? <section className="panel" aria-label="Attendance">
      <h2 className="panel-title">No attendees</h2>
      <p className="panel-sub">Zoom reported no attendees. This session is excluded from attendance.</p>
    </section> : <>
    <dl className="session-metrics" aria-label="Session summary">
      {metrics.map(metric => <div className="panel" key={metric.label}>
        <dt>{metric.label}</dt><dd><strong>{metric.value}</strong></dd>
      </div>)}
    </dl>
    {facts && <SessionOnlineTimeline history={facts.onlineHistory ?? []} start={sessionStart} end={chartEnd}
      endLabel={actualEnd ? "End" : runningWindow ? "Now" : showLastSample ? "Last sample" : "Scheduled end"} timeZone={session.timeZone} />}
    <section className="panel panel-gap" aria-labelledby="attendance-title">
      <h2 id="attendance-title" className="panel-title">{!facts ? "ATTENDANCE" : finalised ? "FINAL ATTENDANCE"
        : facts.status === "RECONCILED" ? "ATTENDANCE FROM ZOOM’S REPORT" : "PROVISIONAL ATTENDANCE"}</h2>
      {!facts && <p className="session-error" role="alert">Attendance couldn’t load. <PanelRetry /></p>}
      {facts && !finalised && <p className="panel-sub">{facts.status === "RECONCILED"
        ? "From Zoom’s participant report, fetched after the webinar ended. No present or absent decision is made until finalisation."
        : "Provisional Zoom records. A missing join is not proof of absence."}</p>}
      {showPoll && <p className="muted live-refresh">Updating every 30 seconds while the class runs ·{" "}
        <button type="button" className="text-link" onClick={() => setPaused(value => !value)}>{paused ? "Resume" : "Pause"}</button>
        {delayed && <span role="status"> · Live updates delayed</span>}
      </p>}
      {facts && !finalised && facts.status === "RECONCILED" && !cancelled && sessionEnd <= clock && <FinaliseForm sessionId={session.id} />}
      {facts && <SessionAttendanceRoster students={students} sessionId={session.id} timeZone={session.timeZone} finalised={finalised} />}
    </section>
    </>}
    <div className={run ? "staff-grid panel-gap" : "panel-gap"}>
      {children}
      {run && <ZoomRunPanel run={run} timeZone={session.timeZone} now={clock} />}
    </div>
    {engagement?.enabled && <EngagementPanel data={engagement} sessionId={session.id} cancelled={cancelled} />}
  </>;
}

function ZoomRunPanel({ run, timeZone, now }: { run: ClassRun; timeZone: string; now: number }) {
  const began = runStart(run);
  return <section className="panel" aria-labelledby="zoom-run-title">
    <h2 id="zoom-run-title" className="panel-title">ZOOM RUN</h2>
    <p className="panel-sub">This class was created when Zoom actually started webinar {formatWebinarId(run.webinarId)}. Times come from Zoom;
      LUMA does not change them.</p>
    <dl className="zoom-run-facts">
      <div><dt>Status</dt><dd>{run.state === "LIVE" ? <span className="pill pill-live">Live</span> : "Ended"}</dd></div>
      <div><dt>Actual start</dt><dd>{formatTime(began.at, timeZone)}{began.known ? "" : " (first event; Zoom’s start not received)"}</dd></div>
      <div><dt>Actual end</dt><dd>{run.actualEndedAt ? formatTime(run.actualEndedAt, timeZone) : "—"}</dd></div>
      <div><dt>{run.state === "LIVE" ? "Running for" : "Length"}</dt>
        <dd>{formatElapsed(((run.actualEndedAt ? Date.parse(run.actualEndedAt) : now) - Date.parse(began.at)) / 1000)}</dd></div>
      <div><dt>Zoom schedule</dt><dd>{run.scheduledStartAt ? `${formatWhen(run.scheduledStartAt, timeZone)}${run.scheduledDurationMinutes
        ? ` · ${run.scheduledDurationMinutes} min` : ""}` : "No Zoom schedule"}</dd></div>
      {run.state === "LIVE" && <div><dt>Connected now</dt><dd>{run.connectedNow}</dd></div>}
      <div><dt>Joined</dt><dd>{run.people} {run.people === 1 ? "person" : "people"}</dd></div>
      <div><dt>Zoom’s report</dt><dd>{run.reported ? "Received (final source)" : run.state === "LIVE" ? "After the webinar" : "Waiting for Zoom"}</dd></div>
      <div><dt>Academy class</dt><dd>{run.classResolved ? run.academyClassName : "Unresolved: no students expected"}</dd></div>
    </dl>
    <p className="link-row"><Link className="text-link" href="/staff/sessions">Tracked Zoom links →</Link></p>
  </section>;
}

function EngagementPanel({ data, sessionId, cancelled }: { data: ClassEngagement; sessionId: string; cancelled: boolean }) {
  return <section className="panel panel-gap" aria-labelledby="engagement-title">
    <h2 id="engagement-title" className="panel-title">ENGAGEMENT</h2>
    <p className="panel-sub">From the student portal, consenting students only ({data.reactingStudents} reacted). Source: {data.source}.</p>
    <ul className="dir-list reaction-counts">{Object.entries(data.reactions).map(([kind, count]) =>
      <li key={kind} className="dir-row"><span>{reactionLabels[kind] ?? kind}</span><strong>{count}</strong></li>)}</ul>
    <h3 className="form-title">Questions</h3>
    <ul className="dir-list">{data.pulses.map(q => <li key={q.id} className="dir-course pulse" aria-label={`Question ${q.prompt}`}>
      <div className="dir-row"><strong>{q.prompt}</strong><span className={`status ${q.open ? "on" : "off"}`}>{q.open ? "Open" : "Closed"}</span></div>
      <ul className="dir-list">{q.options.map((o, i) => <li key={i} className="dir-row"><span>{o}</span><strong>{q.counts?.[i] ?? 0}</strong></li>)}</ul>
      {q.open && <ClosePulseButton sessionId={sessionId} questionId={q.id} />}
    </li>)}
      {data.pulses.length === 0 && <li className="dir-empty muted">No questions yet.</li>}</ul>
    {!cancelled && <PulseForm sessionId={sessionId} />}
  </section>;
}
