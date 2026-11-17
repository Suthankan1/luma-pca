"use client";

import { useActionState } from "react";
import { correctRecord, finaliseAllReady, finaliseAttendance, recalculateAttendance, resolveAppeal, savePolicy } from "@/app/staff/attendance/actions";
import { attendanceOutcomes, outcomeLabels, type AttendanceOutcome, type AttendancePolicy, type FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

export function PolicyForm({ policy }: { policy: AttendancePolicy | null }) {
  const [state, action, pending] = useActionState(savePolicy, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form">
    {policy && <input type="hidden" name="version" value={policy.version} />}
    <label htmlFor="policy-percent">Present when connected for at least (% of the class time)</label>
    <input id="policy-percent" name="minConnectedPercent" type="number" min={1} max={100} required
      defaultValue={values?.minConnectedPercent ?? policy?.minConnectedPercent ?? ""} />
    <label htmlFor="policy-late">Late when the first join is more than (minutes after the start)</label>
    <input id="policy-late" name="lateAfterMinutes" type="number" min={0} max={240} required
      defaultValue={values?.lateAfterMinutes ?? policy?.lateAfterMinutes ?? ""} />
    <label htmlFor="policy-description">Where this rule was approved</label>
    <input id="policy-description" name="description" maxLength={500} required autoComplete="off"
      defaultValue={values?.description ?? policy?.description ?? ""} placeholder="Approved at the staff meeting on 1 Nov 2026" />
    <label className="check"><input type="checkbox" name="autoFinalise" defaultChecked={!!policy?.autoFinalise} /> Finalise each class
      automatically once Zoom’s report is in (in your name; staff can still correct and recalculate)</label>
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save rule"}</button>
    <Feedback state={state} id="policy-feedback" />
  </form>;
}

export function FinaliseAllForm({ ready }: { ready: number }) {
  const [state, action, pending] = useActionState(finaliseAllReady, null);
  return <form action={action} className="inline-form">
    <button className="action" type="submit" disabled={pending || ready === 0}>
      {pending ? "Finalising…" : `Finalise ${ready} ready class${ready === 1 ? "" : "es"}`}</button>
    <Feedback state={state} id="finalise-all-feedback" />
  </form>;
}

export function FinaliseForm({ sessionId }: { sessionId: string }) {
  const [state, action, pending] = useActionState(finaliseAttendance, null);
  return <form action={action} className="inline-form">
    <input type="hidden" name="sessionId" value={sessionId} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Finalising…" : "Finalise attendance"}</button>
    <Feedback state={state} id="finalise-feedback" />
  </form>;
}

export function CorrectionForm({ prefix, sessionId, recordId, version, current }: {
  prefix: string; sessionId: string; recordId: string; version: number; current: AttendanceOutcome;
}) {
  const [state, action, pending] = useActionState(correctRecord, null);
  const values = state && !state.ok ? state.values : undefined;
  const choices = attendanceOutcomes.filter(o => o !== current);
  return <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
    <input type="hidden" name="recordId" value={recordId} />
    <input type="hidden" name="sessionId" value={sessionId} />
    <input type="hidden" name="version" value={version} />
    <label htmlFor={`${prefix}-outcome`}>Corrected outcome</label>
    <select id={`${prefix}-outcome`} name="outcome" defaultValue={values?.outcome ?? choices[0]}>
      {choices.map(o => <option key={o} value={o}>{outcomeLabels[o]}</option>)}
    </select>
    <label htmlFor={`${prefix}-reason`}>Reason <span className="muted">(recorded)</span></label>
    <input id={`${prefix}-reason`} name="reason" maxLength={500} required autoComplete="off" defaultValue={values?.reason}
      placeholder="Medical note received" />
    <button className="action secondary" type="submit" disabled={pending}>{pending ? "Saving…" : "Save correction"}</button>
    <Feedback state={state} id={`${prefix}-feedback`} />
  </form>;
}

export function AppealDecisionForm({ prefix, appealId, current }: { prefix: string; appealId: string; current: AttendanceOutcome }) {
  const [state, action, pending] = useActionState(resolveAppeal, null);
  const values = state && !state.ok ? state.values : undefined;
  const choices = attendanceOutcomes.filter(o => o !== current);
  return <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
    <input type="hidden" name="appealId" value={appealId} />
    <label htmlFor={`${prefix}-outcome`}>Outcome if accepted</label>
    <select id={`${prefix}-outcome`} name="outcome" defaultValue={values?.outcome ?? choices[0]}>
      {choices.map(o => <option key={o} value={o}>{outcomeLabels[o]}</option>)}
    </select>
    <label htmlFor={`${prefix}-note`}>Note to the student</label>
    <input id={`${prefix}-note`} name="note" maxLength={500} required autoComplete="off" defaultValue={values?.note} />
    <div className="button-row">
      <button className="action" type="submit" name="decision" value="ACCEPT" disabled={pending}>Accept</button>
      <button className="action secondary" type="submit" name="decision" value="REJECT" disabled={pending}>Reject</button>
    </div>
    <Feedback state={state} id={`${prefix}-feedback`} />
  </form>;
}

export function RecalculateForm({ sessionId }: { sessionId: string }) {
  const [state, action, pending] = useActionState(recalculateAttendance, null);
  const values = state && !state.ok ? state.values : undefined;
  return <details className="decision recalculate"><summary>Recalculate from Zoom’s report</summary>
    <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
      <input type="hidden" name="sessionId" value={sessionId} />
      <p className="panel-sub">Use this after Zoom’s report was fetched again or another run was linked. Corrected records keep their outcome.</p>
      <label htmlFor="recalc-reason">Reason <span className="muted">(recorded)</span></label>
      <input id="recalc-reason" name="reason" maxLength={300} required autoComplete="off" defaultValue={values?.reason} />
      <button className="action secondary" type="submit" disabled={pending}>{pending ? "Recalculating…" : "Recalculate"}</button>
      <Feedback state={state} id="recalc-feedback" />
    </form></details>;
}
