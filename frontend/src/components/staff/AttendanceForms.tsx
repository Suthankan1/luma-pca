"use client";

import { useActionState } from "react";
import { finaliseAttendance, savePolicy } from "@/app/staff/attendance/actions";
import type { AttendancePolicy, FormState } from "@/lib/staff-forms";

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
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save rule"}</button>
    <Feedback state={state} id="policy-feedback" />
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
