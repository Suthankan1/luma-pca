"use client";

import { useActionState } from "react";
import { assignPaymentRoster } from "@/app/staff/sessions/webinar-actions";
import type { AcademyClass } from "@/lib/academy";

export function PaymentRosterForm({ monitorId, webinarId, assigned, classes }: {
  monitorId?: string; webinarId?: string; assigned: string; classes: AcademyClass[] | null;
}) {
  const [state, action, pending] = useActionState(assignPaymentRoster, null);
  const source = classes?.find(item => item.className === assigned);
  const synced = !!monitorId && !!source?.batch?.active && !!source.batch.lastSyncedAt
    && source.batch.lastSyncedStudents === source.studentCount;
  const available = !!classes?.length;

  return <form action={action} className="class-type-roster-inline">
    {monitorId && <input type="hidden" name="monitorId" value={monitorId} />}
    <span className={synced ? "status on" : "status off"}>
      {!classes ? "Sync unavailable" : synced ? "Synced" : monitorId ? "Sync needed" : "Zoom link not tracked"}
    </span>
    {synced && <span className="muted">Expected per run: {source.studentCount}</span>}
    <label className="academy-link">Academy payment class
      <select key={state?.ok === false ? state.values?.academyClassName : assigned}
        name="academyClassName" defaultValue={state?.values?.academyClassName ?? source?.className ?? ""} required disabled={pending || !available}>
        <option value="">{!classes ? "Academy database unavailable" : available ? "Select payment class" : "No payment classes found"}</option>
        {classes?.map(item => <option key={item.className} value={item.className}>{item.className}</option>)}
      </select>
    </label>
    {!monitorId && (webinarId
      ? <input type="hidden" name="webinar" value={webinarId} />
      : <label className="academy-link">Zoom link or webinar ID
          <input name="webinar" required maxLength={500} disabled={pending || !available} autoComplete="off" />
        </label>)}
    <button className="action secondary" type="submit" disabled={pending || !available}>
      {pending ? "Syncing…" : monitorId ? "Sync" : "Track and sync"}
    </button>
    {state && <p className={state.ok ? "muted" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
  </form>;
}
