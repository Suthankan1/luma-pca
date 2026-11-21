"use client";

import { useActionState } from "react";
import { assignPaymentRoster } from "@/app/staff/sessions/webinar-actions";
import type { AcademyClass } from "@/lib/academy";

export function PaymentRosterForm({ monitorId, assigned, classes }: {
  monitorId: string; assigned: string; classes: AcademyClass[];
}) {
  const [state, action, pending] = useActionState(assignPaymentRoster, null);
  const source = classes.find(item => item.className === assigned);
  const synced = !!source?.batch?.active && !!source.batch.lastSyncedAt
    && source.batch.lastSyncedStudents === source.studentCount;

  return <form action={action} className="class-type-roster-inline">
    <input type="hidden" name="monitorId" value={monitorId} />
    <span className={synced ? "status on" : "status off"}>{synced ? "Synced" : "Sync needed"}</span>
    {synced && <span className="muted">Expected per run: {source.studentCount}</span>}
    <label className="academy-link">Academy payment class
      <select key={assigned} name="academyClassName" defaultValue={source?.className ?? ""} required disabled={pending}>
        <option value="">Select payment class</option>
        {classes.map(item => <option key={item.className} value={item.className}>{item.className}</option>)}
      </select>
    </label>
    <button className="action secondary" type="submit" disabled={pending || classes.length === 0}>
      {pending ? "Syncing…" : "Sync"}
    </button>
    {state && <p className={state.ok ? "muted" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
  </form>;
}
