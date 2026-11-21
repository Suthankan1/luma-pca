"use client";

import { useActionState } from "react";
import { academyClassAction } from "@/app/staff/academy/actions";
import type { SyncResult } from "@/lib/academy";

function IdList({ label, ids }: { label: string; ids: string[] }) {
  if (ids.length === 0) return null;
  return <li>{label}: <span className="mono">{ids.join(", ")}</span></li>;
}

/** What a sync found that staff may need to act on. Nothing here was changed automatically. */
function SyncDetails({ sync }: { sync: SyncResult }) {
  const notes = sync.withoutName || sync.withoutEmail || sync.withdrawnInLuma.length || sync.inactiveInLuma.length
    || sync.invalidIds.length || sync.notInAcademyClass.length;
  if (!notes) return null;
  return <ul className="sync-notes" aria-label="Needs attention">
    {sync.withoutName > 0 && <li>{sync.withoutName} without a single name in the Academy records (shown by student ID).</li>}
    {sync.withoutEmail > 0 && <li>{sync.withoutEmail} new without a usable email of their own: confirm them under Zoom review
      the first time they join a webinar.</li>}
    <IdList label="Left the Academy class but still enrolled here (decide on the batch roster)" ids={sync.notInAcademyClass} />
    <IdList label="Withdrawn in LUMA by staff (kept withdrawn)" ids={sync.withdrawnInLuma} />
    <IdList label="Deactivated in LUMA (not enrolled)" ids={sync.inactiveInLuma} />
    <IdList label="Academy IDs LUMA cannot use (not imported)" ids={sync.invalidIds} />
  </ul>;
}

/** Set up, link or sync one Academy class. One action state per row, so the outcome stays after the page refreshes. */
export function AcademyClassActions({ className, slug, linked, batches, selectedBatch, rosterControls = false }: {
  className: string; slug: string; linked: boolean; batches: { id: string; label: string; disabled?: boolean }[];
  selectedBatch?: { id: string; label: string };
  rosterControls?: boolean;
}) {
  const [state, action, pending] = useActionState(academyClassAction, null);
  const feedback = `academy-feedback-${slug}`;
  const canSync = !!selectedBatch || batches.some(batch => !batch.disabled);
  return <div className="academy-actions">
    {!rosterControls && (linked
      ? <form action={action} className="inline-form">
          <input type="hidden" name="intent" value="sync" />
          <input type="hidden" name="className" value={className} />
          <button className="action secondary" type="submit" disabled={pending} aria-label={`Sync students of ${className}`}
            aria-describedby={feedback}>{pending ? "Syncing…" : "Sync students"}</button>
        </form>
      : <>
          <form action={action} className="inline-form">
            <input type="hidden" name="intent" value="setup" />
            <input type="hidden" name="className" value={className} />
            <button className="action" type="submit" disabled={pending} aria-describedby={feedback}>
              {pending ? "Setting up…" : "Set up in LUMA"}</button>
          </form>
        </>)}
    {(rosterControls || (!linked && batches.length > 0)) && <form action={action} className="academy-link">
      <input type="hidden" name="intent" value="link" />
      <input type="hidden" name="className" value={className} />
      <label htmlFor={`link-${slug}`}>{rosterControls ? "Student batch" : "Or use an existing batch"}</label>
      <div className="inline-form">
        <select key={selectedBatch?.id} id={`link-${slug}`} name="batchId" required defaultValue={selectedBatch?.id ?? ""}
          disabled={pending || batches.length === 0 && !selectedBatch}>
          <option value="" disabled>{canSync ? "Choose a batch" : "No selectable batch"}</option>
          {selectedBatch && <option value={selectedBatch.id}>{selectedBatch.label}</option>}
          {batches.map(batch => <option key={batch.id} value={batch.id} disabled={batch.disabled}>{batch.label}</option>)}
        </select>
        <button className="action secondary" type="submit" disabled={pending || !canSync}>
          {pending ? "Syncing…" : rosterControls ? "Sync" : "Link and sync"}
        </button>
      </div>
    </form>}
    <div id={feedback} role={state?.ok === false ? "alert" : "status"} className="academy-feedback">
      {state && <p className={state.ok ? "form-ok" : "session-error"}>{state.message}</p>}
      {state?.sync && <SyncDetails sync={state.sync} />}
    </div>
  </div>;
}
