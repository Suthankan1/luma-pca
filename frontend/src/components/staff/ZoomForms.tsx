"use client";

import { useActionState } from "react";
import { autoLinkOccurrences, decideOccurrence, replayReceipt } from "@/app/staff/zoom/actions";
import type { FormState } from "@/lib/staff-forms";

function Feedback({ state, className }: { state: FormState; className: string }) {
  if (!state) return null;
  return <p className={`${className} ${state.ok ? "form-ok" : "session-error"}`} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

/**
 * One decision per occurrence: link it to a class, or record that it was not a class.
 * `prefix` keeps element ids unique when the list shows many occurrences.
 */
export function DecisionForm({ prefix, occurrenceUuid, candidates, current }: {
  prefix: string; occurrenceUuid: string; candidates: { id: string; label: string }[]; current: string | null;
}) {
  const [state, action, pending] = useActionState(decideOccurrence, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
    <input type="hidden" name="occurrenceUuid" value={occurrenceUuid} />
    {candidates.length > 0 ? <>
      <label htmlFor={`${prefix}-class`}>Class</label>
      <select id={`${prefix}-class`} name="sessionId" defaultValue={values?.sessionId ?? current ?? candidates[0].id}>
        {candidates.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
      </select>
    </> : <p className="panel-sub">No scheduled class starts within a day of this webinar. Schedule it, or add the webinar ID to it and run automatic linking.</p>}
    <label htmlFor={`${prefix}-reason`}>Reason <span className="muted">(recorded; no names or emails)</span></label>
    <input id={`${prefix}-reason`} name="reason" maxLength={300} required autoComplete="off" defaultValue={values?.reason}
      placeholder="Checked against the timetable" />
    <div className="button-row">
      {candidates.length > 0 && <button className="action" type="submit" name="intent" value="link" disabled={pending}>Link to class</button>}
      <button className="action secondary" type="submit" name="intent" value="not-class" disabled={pending}>Not a class</button>
    </div>
    <Feedback state={state} className="decision-feedback" />
  </form>;
}

export function AutoLinkForm() {
  const [state, action, pending] = useActionState(autoLinkOccurrences, null);
  return <form action={action} className="inline-form">
    <button className="action secondary" type="submit" disabled={pending}>{pending ? "Linking…" : "Run automatic linking"}</button>
    <Feedback state={state} className="autolink-feedback" />
  </form>;
}

export function ReplayForm({ prefix, id }: { prefix: string; id: string }) {
  const [state, action, pending] = useActionState(replayReceipt, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
    <input type="hidden" name="id" value={id} />
    <label htmlFor={`${prefix}-reason`}>Reason <span className="muted">(recorded; no names or emails)</span></label>
    <input id={`${prefix}-reason`} name="reason" maxLength={300} required autoComplete="off" defaultValue={values?.reason}
      placeholder="Cause fixed in release 1.4" />
    <button className="action secondary" type="submit" disabled={pending}>{pending ? "Queuing…" : "Replay"}</button>
    <Feedback state={state} className="replay-feedback" />
  </form>;
}
