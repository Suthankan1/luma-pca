"use client";

import { useActionState } from "react";
import { appealRecord } from "@/app/student/actions";

export function AppealForm({ prefix, recordId }: { prefix: string; recordId: string }) {
  const [state, action, pending] = useActionState(appealRecord, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
    <input type="hidden" name="recordId" value={recordId} />
    <label htmlFor={`${prefix}-message`}>What should the academy know?</label>
    <textarea id={`${prefix}-message`} name="message" maxLength={1000} required rows={3} defaultValue={values?.message} />
    <button className="action secondary" type="submit" disabled={pending}>{pending ? "Sending…" : "Send appeal"}</button>
    {state && <p id={`${prefix}-feedback`} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
  </form>;
}
