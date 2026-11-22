import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { SessionAttendancePanel } from "@/components/staff/SessionAttendancePanel";
import { formatTime, formatWhen, runStart, type ClassRun } from "@/lib/webinars";
import { renderTime } from "@/lib/insights";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { compactClassTitle } from "@/lib/staff-home";
import {
  occurrencePeopleHref, occurrenceSource, formatClassTime, formatInstant, formatWebinarId, occurrenceState, uuidOrNull,
  type ClassEngagement, type ClassSession, type Occurrence, type ProvisionalAttendance,
} from "@/lib/staff-forms";

export const metadata = { title: "Class · LUMA" };
const attendanceTimeoutMs = 25_000;

/** One class for staff: its students, Zoom run, attendance (with corrections) and engagement questions. */
export default async function SessionPage({ params, searchParams }: {
  params: Promise<{ sessionId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const identity = await requirePortal("staff");
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) notFound();
  const [session, zoom, attendance, engagement, zoomRun, query] = await Promise.all([
    staffApi<ClassSession>(`/api/v1/class-sessions/${sessionId}`),
    staffApi<Occurrence[]>(`/api/v1/zoom/occurrences?decided=true&sessionId=${sessionId}&limit=50`),
    staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${sessionId}/attendance?includeOnlineHistory=true`, undefined, attendanceTimeoutMs),
    staffApi<ClassEngagement>(`/api/v1/class-sessions/${sessionId}/engagement`),
    staffApi<ClassRun | null>(`/api/v1/class-sessions/${sessionId}/zoom-run`),
    searchParams,
  ]);
  if (!session.ok && (session.status === 404 || session.status === 403)) notFound();
  const fromClassTypes = query.source === "class-types";
  const parent = fromClassTypes ? "Class Types" : "Classes";
  const backQuery = new URLSearchParams();
  if (typeof query.month === "string" && /^\d{4}-(0[1-9]|1[0-2])$/.test(query.month)) backQuery.set("month", query.month);
  const classType = typeof query.type === "string" && query.type.length > 0 && query.type.length <= 300 ? query.type : null;
  if (classType) {
    backQuery.set("type", classType);
    if (fromClassTypes) backQuery.set("source", "class-types");
  }
  const backPath = classType ? "/staff/sessions/types" : fromClassTypes ? "/staff/class-types" : "/staff/sessions";
  const shell = { active: fromClassTypes ? "class-types" as const : "sessions" as const, crumb: `Teaching / ${parent}`,
    hasStudentPortal: hasPortal(identity, "student"),
    actions: <Link className="text-link" href={backQuery.size ? `${backPath}?${backQuery}` : backPath}>← Back to {classType ? "class type" : parent}</Link> };
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
  const running = run?.state === "LIVE";
  const actualEnd = run?.actualEndedAt;
  const subtitle = run && runBegan
    ? `${running ? "Live now · " : ""}${formatWhen(runBegan.at, s.timeZone)} ${runBegan.known ? "actual start" : "first Zoom event"}${actualEnd ? `, ended ${formatTime(actualEnd, s.timeZone)}` : ""} (${s.timeZone})`
    : `${formatClassTime(s.startsAt, s.endsAt, s.timeZone)} (${s.timeZone})${cancelled ? " · Cancelled" : ""}`;
  return <StaffShell {...shell} title={compactClassTitle(s.title)} fullTitle={s.title} subtitle={subtitle}>
    <SessionAttendancePanel session={s} key={s.id} initialRun={run} now={now} initial={attendance.ok ? attendance.data : null}
      initialEngagement={engagement.ok ? engagement.data : null}>
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
    </SessionAttendancePanel>
  </StaffShell>;
}
