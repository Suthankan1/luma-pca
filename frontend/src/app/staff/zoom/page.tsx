import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { AutoLinkForm, DecisionForm, IdentityForm, ReplayForm, ReportForm } from "@/components/staff/ZoomForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  candidateClasses, candidateWindow, formatClassTime, formatInstant, formatWebinarId, occurrencePeopleHref, occurrenceSource, occurrenceState, reportState, zoomEventLabels,
  type ClassSession, type Occurrence, type UnmatchedZoomIdentity, type ZoomReceipt,
} from "@/lib/staff-forms";

export const metadata = { title: "Zoom review · LUMA" };

const views = [
  { key: "all", label: "All", query: "" },
  { key: "review", label: "Needs review", query: "&decided=false" },
  { key: "decided", label: "Decided", query: "&decided=true" },
] as const;

const LIMIT = 50;

/**
 * Staff review what the webhook worker could not settle on its own:
 * webinar occurrences not linked to a class, Zoom accounts that match no student, and
 * receipts that failed processing. Receipt payloads are never shown; Zoom names and emails
 * appear only where staff must match people.
 */
export default async function ZoomReviewPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const requested = (await searchParams).view;
  const view = views.find(v => v.key === requested) ?? views[0];
  const shell = { active: "zoom" as const, crumb: "Zoom", title: "Zoom review",
    subtitle: "Webinar occurrences become attendance only once they are linked to a class. Nothing here is shown to students.",
    hasStudentPortal: hasPortal(identity, "student") };

  const [occurrences, dead, waiting, unmatched] = await Promise.all([
    staffApi<Occurrence[]>(`/api/v1/zoom/occurrences?limit=${LIMIT}${view.query}`),
    staffApi<ZoomReceipt[]>(`/api/v1/zoom/receipts?status=DEAD&limit=${LIMIT}`),
    staffApi<ZoomReceipt[]>("/api/v1/zoom/receipts?status=RECEIVED&limit=200"),
    staffApi<UnmatchedZoomIdentity[]>(`/api/v1/zoom/participants/unmatched?limit=${LIMIT}`),
  ]);
  if (!occurrences.ok || !dead.ok || !waiting.ok || !unmatched.ok) {
    return <StaffShell {...shell}>
      <p className="session-error" role="alert">We couldn’t load Zoom events right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  // Class choices: scheduled classes within a day of each occurrence, from one list request.
  const window = candidateWindow(occurrences.data);
  const sessions = window
    ? await staffApi<ClassSession[]>(`/api/v1/class-sessions?${new URLSearchParams({ from: window.from, to: window.to })}`)
    : null;
  const nearby = sessions?.ok ? sessions.data : [];
  // Linked classes may lie outside that window; fetch the few that do for their titles.
  const known = new Map(nearby.map(s => [s.id, s]));
  const missing = [...new Set(occurrences.data.map(o => o.classSessionId).filter((id): id is string => !!id && !known.has(id)))];
  for (const result of await Promise.all(missing.map(id => staffApi<ClassSession>(`/api/v1/class-sessions/${id}`)))) {
    if (result.ok) known.set(result.data.id, result.data);
  }
  const reviewCount = occurrences.data.filter(o => !o.linkSource).length;

  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="occurrences-title">
      <h2 id="occurrences-title" className="panel-title">WEBINAR OCCURRENCES</h2>
      <p className="panel-sub">Each time a webinar runs, Zoom reports an occurrence. Runs of monitored webinars become classes by themselves;
        this list is for the rest (webinars that are not monitored, or classes from before). Link an occurrence to a class or mark it as not
        a class; your decisions are never overridden automatically.</p>
      <nav className="filter-row" aria-label="Filter occurrences">
        {views.map(v => <Link key={v.key} href={v.key === "all" ? "/staff/zoom" : `/staff/zoom?view=${v.key}`}
          className={`chip ${v.key === view.key ? "cyan" : ""}`} aria-current={v.key === view.key ? "true" : undefined}>{v.label}</Link>)}
      </nav>
      <div className="link-row"><AutoLinkForm /><Link className="text-link" href="/staff/zoom/check">Check a webinar by link or ID →</Link>
        <Link className="text-link" href="/staff/sessions">Tracked Zoom links →</Link></div>
      {sessions && !sessions.ok && <p className="session-error" role="alert">Class choices couldn’t be loaded; reload to decide.</p>}
      <ul className="dir-list">
        {occurrences.data.map((o, index) => {
          const linked = o.classSessionId ? known.get(o.classSessionId) : undefined;
          const state = occurrenceState(o);
          return <li key={o.occurrenceUuid} className="dir-course occurrence" aria-label={`Webinar ${formatWebinarId(o.webinarId)} at ${formatInstant(o.firstEventAt)}`}>
            <div className="dir-row">
              <div>
                <strong>Webinar {formatWebinarId(o.webinarId)}</strong>
                <span className="dir-dates muted"> {formatInstant(o.firstEventAt)} – {formatInstant(o.lastEventAt)} · {occurrenceSource(o)}</span>
                {o.classSessionId && <div className="dir-dates">
                  {linked ? <Link className="text-link" href={`/staff/sessions/${linked.id}`}>{linked.title}, {formatClassTime(linked.startsAt, linked.endsAt, linked.timeZone)}</Link>
                    : <Link className="text-link" href={`/staff/sessions/${o.classSessionId}`}>Linked class</Link>}
                </div>}
              </div>
              <span className={`status ${state === "Needs review" ? "off" : "on"}`}>{state}</span>
            </div>
            <p className="dir-dates"><Link className="text-link" href={occurrencePeopleHref(o)}>People in this occurrence</Link></p>
            <p className="dir-dates muted report-state">{reportState(o)}</p>
            {o.ended && <ReportForm occurrenceUuid={o.occurrenceUuid} again={o.reportStatus === "FETCHED"} />}
            {o.linkSource ? <details key="change" className="decision">
              <summary>Change decision</summary>
              <DecisionForm prefix={`occ-${index}`} occurrenceUuid={o.occurrenceUuid} candidates={candidateClasses(o, nearby)} current={o.classSessionId} />
            </details> : <details key="decide" className="decision">
              <summary>Decide: link to a class or mark not a class</summary>
              <DecisionForm prefix={`occ-${index}`} occurrenceUuid={o.occurrenceUuid} candidates={candidateClasses(o, nearby)} current={null} />
            </details>}
          </li>;
        })}
        {occurrences.data.length === 0 && <li className="dir-empty muted">{view.key === "review" ? "Nothing needs review." : "No webinar occurrences yet."}</li>}
      </ul>
      {occurrences.data.length === LIMIT && <p className="panel-sub">Showing the {LIMIT} most recent{view.key === "review" ? "" : `; ${reviewCount} of them need review`}.</p>}
    </section>

    <section className="panel panel-gap" aria-labelledby="identities-title">
      <h2 id="identities-title" className="panel-title">ZOOM ACCOUNTS TO MATCH</h2>
      <p className="panel-sub">Zoom emails that match no student’s sign-in email. Match each to a student, or mark it as not a student
        (a teacher, guest or test account). A decision applies to every webinar and is recorded with its reason.</p>
      <ul className="dir-list">
        {unmatched.data.map((u, index) => <li key={u.email} className="dir-course identity" aria-label={`Zoom account ${u.email}`}>
          <div className="dir-row">
            <div><strong>{u.displayName ?? "Unknown name"}</strong>
              <span className="dir-dates muted"> {u.email} · {u.occurrences} occurrence{u.occurrences === 1 ? "" : "s"} · last seen {formatInstant(u.lastSeenAt)}</span></div>
            <span className="status off">Not matched</span>
          </div>
          <details className="decision">
            <summary>Match to a student or mark not a student</summary>
            <IdentityForm prefix={`identity-${index}`} email={u.email} />
          </details>
        </li>)}
        {unmatched.data.length === 0 && <li className="dir-empty muted">Every Zoom account seen so far is matched or decided.</li>}
      </ul>
    </section>

    <section className="panel panel-gap" aria-labelledby="receipts-title">
      <h2 id="receipts-title" className="panel-title">FAILED EVENTS</h2>
      <p className="panel-sub">Events the worker gave up on after retrying, or could not read. Replay one once its cause is fixed.
        {" "}{waiting.data.length >= 200 ? "200 or more" : waiting.data.length} event{waiting.data.length === 1 ? " is" : "s are"} waiting to be processed.</p>
      <ul className="dir-list">
        {dead.data.map((r, index) => <li key={r.id} className="dir-course receipt" aria-label={`${zoomEventLabels[r.eventType] ?? r.eventType} received ${formatInstant(r.receivedAt)}`}>
          <div className="dir-row">
            <div><strong>{zoomEventLabels[r.eventType] ?? r.eventType}</strong>
              <span className="dir-dates muted"> received {formatInstant(r.receivedAt)} · {r.attempts} attempt{r.attempts === 1 ? "" : "s"}</span>
              {r.lastError && <div className="dir-dates"><code>{r.lastError}</code></div>}</div>
            <span className="status off">Failed</span>
          </div>
          <details className="decision">
            <summary>Replay this event</summary>
            <ReplayForm prefix={`receipt-${index}`} id={r.id} />
          </details>
        </li>)}
        {dead.data.length === 0 && <li className="dir-empty muted">No failed events.</li>}
      </ul>
    </section>
  </StaffShell>;
}
