import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { IdentityForm, RemoveDecisionForm } from "@/components/staff/ZoomForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  formatClassTime, formatInstant, isAcademyManager, occurrenceIdOrNull, participantMatchLabel,
  type ClassSession, type OccurrenceParticipants,
} from "@/lib/staff-forms";

export const metadata = { title: "Zoom participants · LUMA" };

/**
 * Who joined one webinar occurrence and which student each person is. Live Zoom data is
 * provisional: this is not attendance, and someone missing here is not proof of absence.
 */
export default async function ZoomParticipantsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const occurrence = occurrenceIdOrNull((await searchParams).occurrence as string ?? null);
  const shell = { active: "zoom" as const, crumb: "Zoom", title: "Zoom participants",
    subtitle: "People Zoom reported for one webinar occurrence. Provisional live data, not attendance.",
    hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}><p className="banner">Only academy owners and administrators review Zoom events.</p></StaffShell>;
  }
  if (!occurrence) {
    return <StaffShell {...shell}><p className="session-error" role="alert">Choose an occurrence on the <Link className="text-link" href="/staff/zoom">Zoom review</Link> page.</p></StaffShell>;
  }
  const result = await staffApi<OccurrenceParticipants>(`/api/v1/zoom/participants?${new URLSearchParams({ occurrence })}`);
  if (!result.ok) {
    return <StaffShell {...shell}>
      <p className="session-error" role="alert">{result.status === 404 ? "That occurrence was not found." : "We couldn’t load the participants right now. Please reload shortly."}</p>
      <p><Link className="text-link" href="/staff/zoom">Back to Zoom review</Link></p>
    </StaffShell>;
  }
  const { participants, classSessionId } = result.data;
  const session = classSessionId ? await staffApi<ClassSession>(`/api/v1/class-sessions/${classSessionId}`) : null;
  const counts = {
    students: participants.filter(p => p.match === "STUDENT").length,
    unmatched: participants.filter(p => p.match === "UNMATCHED").length,
    unexpected: participants.filter(p => p.expected === false).length,
  };

  return <StaffShell {...shell}>
    <p><Link className="text-link" href="/staff/zoom">← Zoom review</Link></p>
    <section className="panel" aria-labelledby="participants-title">
      <h2 id="participants-title" className="panel-title">PEOPLE IN THIS OCCURRENCE</h2>
      <p className="panel-sub">
        {session?.ok ? <>Class: <Link className="text-link" href={`/staff/sessions/${session.data.id}`}>{session.data.title}, {formatClassTime(session.data.startsAt, session.data.endsAt, session.data.timeZone)}</Link>. </>
          : classSessionId ? "Linked to a class. " : "Not linked to a class yet, so nobody is checked against a class list. "}
        {counts.students} matched to students, {counts.unmatched} not matched{classSessionId ? `, ${counts.unexpected} student${counts.unexpected === 1 ? "" : "s"} not expected at this class` : ""}.
        People are told apart by the email Zoom reports, never by display name.
      </p>
      <ul className="dir-list">
        {participants.map((p, index) => <li key={p.email ?? `visitor-${index}`} className="dir-course participant"
          aria-label={p.studentNumber ? `${p.studentNumber} ${p.studentName}` : p.email ?? p.displayName ?? "Unknown participant"}>
          <div className="dir-row">
            <div>
              <strong>{p.studentNumber ? `${p.studentNumber} · ${p.studentName}` : p.displayName ?? "Unknown name"}</strong>
              <div className="dir-dates muted">{p.email ?? "Zoom sent no email"}{p.studentNumber && p.displayName ? ` · shown in Zoom as “${p.displayName}”` : ""}</div>
              <div className="dir-dates">{p.visits} visit{p.visits === 1 ? "" : "s"} · {formatInstant(p.firstSeenAt)} – {formatInstant(p.lastSeenAt)}
                {p.openVisits > 0 && <span className="muted"> · no leave received yet</span>}
                {p.expected === false && <span> · not expected at this class</span>}</div>
            </div>
            <span className={`status ${p.match === "UNMATCHED" ? "off" : "on"}`}>{participantMatchLabel(p)}</span>
          </div>
          {p.email && p.match !== "HOST" && (p.match === "UNMATCHED"
            ? <IdentityForm prefix={`person-${index}`} email={p.email} />
            : <details className="decision"><summary>Change match</summary><IdentityForm prefix={`person-${index}`} email={p.email} />
              {p.matchSource === "MANUAL" && <RemoveDecisionForm prefix={`person-${index}`} email={p.email} />}</details>)}
        </li>)}
        {participants.length === 0 && <li className="dir-empty muted">No one joined this occurrence.</li>}
      </ul>
    </section>
  </StaffShell>;
}
