import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { LinkRunForm, RunReportForm } from "@/components/staff/ZoomForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  candidateClasses, formatDuration, formatInstant, formatWebinarId, occurrenceIdOrNull, participantMatchLabel,
  type ClassSession, type Occurrence, type RunAttendance, type WebinarLookup, type ZoomParticipant,
} from "@/lib/staff-forms";

export const metadata = { title: "Check a webinar · LUMA" };

const DAY_MS = 86_400_000;

/**
 * Paste a webinar's join link or ID to see its past runs, check who attended one from Zoom's own
 * participant report, and link the run to a class so its attendance can be finalised there.
 */
export default async function CheckWebinarPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const webinar = typeof params.webinar === "string" ? params.webinar.trim().slice(0, 500) : "";
  const run = occurrenceIdOrNull(typeof params.run === "string" ? params.run : null);
  const shell = { active: "zoom" as const, crumb: "Zoom", title: "Check a webinar",
    subtitle: "Paste a webinar’s join link or ID to see who attended any of its past runs, from Zoom’s own report.",
    hasStudentPortal: hasPortal(identity, "student") };

  const lookup = webinar ? await staffApi<WebinarLookup>(`/api/v1/zoom/webinar-lookup?${new URLSearchParams({ webinar })}`, undefined, 15000) : null;
  const selected = lookup?.ok && run ? lookup.data.runs.find(r => r.occurrenceUuid === run) : undefined;
  const attendance = selected?.reported
    ? await staffApi<RunAttendance>(`/api/v1/zoom/webinar-lookup/attendance?${new URLSearchParams({ webinar, occurrence: selected.occurrenceUuid })}`)
    : null;
  const start = selected?.startTime ? Date.parse(selected.startTime) : null;
  const classes = start ? await staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({
    from: new Date(start - DAY_MS).toISOString(), to: new Date(start + DAY_MS).toISOString() })}`) : null;
  const candidates = selected?.startTime && lookup?.ok && classes?.ok
    ? candidateClasses({ firstEventAt: selected.startTime, webinarId: lookup.data.webinarId } as Occurrence, classes.data) : [];
  const runHref = (uuid: string) => `/staff/zoom/check?${new URLSearchParams({ webinar, run: uuid })}`;

  return <StaffShell {...shell}>
    <p><Link className="text-link" href="/staff/zoom">← Zoom review</Link></p>
    <section className="panel" aria-labelledby="find-title">
      <h2 id="find-title" className="panel-title">FIND A WEBINAR</h2>
      <form method="get" className="session-form">
        <label htmlFor="webinar-input">Join link or webinar ID</label>
        <input id="webinar-input" name="webinar" defaultValue={webinar} required maxLength={500} autoComplete="off"
          placeholder="https://us06web.zoom.us/w/83834428148 or 838 3442 8148" />
        <button className="action" type="submit">Find runs</button>
      </form>
      {lookup && !lookup.ok && <p className="session-error" role="alert">{lookup.detail ?? "We couldn’t reach Zoom right now. Please try again shortly."}</p>}
    </section>

    {lookup?.ok && <section className="panel panel-gap" aria-labelledby="runs-title">
      <h2 id="runs-title" className="panel-title">PAST RUNS</h2>
      <p className="panel-sub">Webinar {formatWebinarId(lookup.data.webinarId)}{lookup.data.topic ? ` · ${lookup.data.topic}` : ""}. Newest first.</p>
      <ul className="dir-list">
        {lookup.data.runs.map(r => <li key={r.occurrenceUuid} className="dir-row dir-course webinar-run"
          aria-label={`Run ${r.startTime ? formatInstant(r.startTime) : "unknown time"}`}>
          <Link className="text-link" href={runHref(r.occurrenceUuid)}>{r.startTime ? formatInstant(r.startTime) : "Unknown time"}</Link>
          <span className={`status ${r.reported ? "on" : "off"}`}>{r.linkedSessionId ? "Linked to a class" : r.reported ? "Report fetched" : "Not checked"}</span>
        </li>)}
        {lookup.data.runs.length === 0 && <li className="dir-empty muted">Zoom lists no past runs of this webinar yet.</li>}
      </ul>
    </section>}

    {selected && <section className="panel panel-gap" aria-labelledby="run-title">
      <h2 id="run-title" className="panel-title">ATTENDANCE</h2>
      <p className="panel-sub">Run of {selected.startTime ? formatInstant(selected.startTime) : "unknown time"}. From Zoom’s participant report;
        people are told apart by email, never by display name. Join sessions of one person are counted once.</p>
      <RunReportForm webinar={webinar} occurrenceUuid={selected.occurrenceUuid} again={selected.reported} />
      {attendance && (!attendance.ok ? <p className="session-error" role="alert">We couldn’t load the report right now.</p> : <>
        <p className="panel-sub">{attendance.data.attendees.filter(a => a.match === "STUDENT").length} students ·{" "}
          {attendance.data.attendees.filter(a => a.match === "UNMATCHED").length} not matched · {attendance.data.joinSessions} join sessions.
          {" "}Match unknown accounts on the <Link className="text-link" href="/staff/zoom">Zoom review</Link> page.</p>
        <ul className="dir-list">
          {attendance.data.attendees.map((a, index) => <li key={a.email ?? `n-${index}`} className="dir-row dir-course run-attendee"
            aria-label={a.studentNumber ? `${a.studentNumber} ${a.studentName}` : a.email ?? a.displayName ?? "Unknown"}>
            <div><strong>{a.studentNumber ? `${a.studentNumber} · ${a.studentName}` : a.displayName ?? "Unknown name"}</strong>
              <div className="dir-dates muted">{a.email ?? "Zoom sent no email"}</div>
              <div className="dir-dates">{formatDuration(a.connectedSeconds)} connected · first joined {formatInstant(a.firstJoinAt)}
                {" "}· {a.joinSessions} join session{a.joinSessions === 1 ? "" : "s"}</div></div>
            <span className={`status ${a.match === "UNMATCHED" ? "off" : "on"}`}>{participantMatchLabel({ ...a, match: a.match } as unknown as ZoomParticipant)}</span>
          </li>)}
        </ul>
        <h3 className="form-title panel-gap">Link to a class</h3>
        <p className="panel-sub">{attendance.data.linkedSessionId
          ? <>Linked: <Link className="text-link" href={`/staff/sessions/${attendance.data.linkedSessionId}`}>open the class</Link> to see expected students and finalise.</>
          : "Linking makes this run the class’s attendance, from Zoom’s report, ready to finalise."}</p>
        <LinkRunForm webinar={webinar} occurrenceUuid={selected.occurrenceUuid} candidates={candidates} current={attendance.data.linkedSessionId} />
      </>)}
    </section>}
  </StaffShell>;
}
