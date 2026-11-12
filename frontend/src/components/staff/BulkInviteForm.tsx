"use client";

import { useEffect, useState, useTransition } from "react";
import { bulkInvite, inviteProgress, type InviteCounts, type InviteProgress } from "@/app/staff/students/actions";

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/**
 * Emails activation invitations to many students: a batch's current students, or every
 * active student. "Check" counts without sending; the emails are then queued and sent in
 * the background, and the progress line refreshes while any are waiting.
 */
export function BulkInviteForm({ batchId, scope }: { batchId: string | null; scope: string }) {
  const [resend, setResend] = useState(false);
  const [checked, setChecked] = useState<{ counts: InviteCounts; resend: boolean } | null>(null);
  const [queued, setQueued] = useState<InviteCounts | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<InviteProgress | null>(null);
  const [pending, startTransition] = useTransition();
  const ready = checked && checked.resend === resend && checked.counts.queued > 0;

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      const next = await inviteProgress().catch(() => null);
      if (stopped) return;
      setProgress(next);
      if (next && next.waiting > 0) timer = setTimeout(poll, 3000);
    };
    void poll();
    return () => { stopped = true; clearTimeout(timer); };
  }, [queued]);

  function run(dryRun: boolean) {
    setError(null);
    startTransition(async () => {
      const result = await bulkInvite({ batchId, resend, dryRun });
      if (!result.ok) { setError(result.message); return; }
      if (dryRun) { setChecked({ counts: result.counts, resend }); setQueued(null); }
      else { setQueued(result.counts); setChecked(null); }
    });
  }

  return <form className="session-form staff-form" onSubmit={event => { event.preventDefault(); run(!ready); }}
    aria-describedby="bulk-invite-feedback">
    <p className="panel-sub">Emails a sign-in link to {scope} whose accounts are not active yet. Each link works for 72 hours.</p>
    <label className="check-row">
      <input type="checkbox" checked={resend} onChange={event => setResend(event.target.checked)} />
      Also send a new link to students who already have one (their earlier link stops working)
    </label>
    <button className="action" type="submit" disabled={pending}>
      {pending ? "Working…" : ready ? `Email ${plural(checked.counts.queued, "invitation")}` : "Check who will be emailed"}
    </button>
    {error && <p id="bulk-invite-feedback" className="session-error" role="alert">{error}</p>}
    {checked && !error && <p id="bulk-invite-feedback" className="form-ok" role="status">
      {checked.counts.queued === 0 ? "Nobody to email." : `${plural(checked.counts.queued, "student")} will be emailed.`}
      {checked.counts.alreadyInvited > 0 && ` ${plural(checked.counts.alreadyInvited, "student")} already ${checked.counts.alreadyInvited === 1 ? "has" : "have"} a usable link.`}
      {checked.counts.alreadyQueued > 0 && ` ${plural(checked.counts.alreadyQueued, "email")} already waiting to be sent.`}
      {" "}Nothing has been sent yet.
    </p>}
    {queued && !error && <p id="bulk-invite-feedback" className="form-ok" role="status">
      {queued.queued === 0 ? "Nothing new to send." : `${plural(queued.queued, "invitation")} queued. They are sent in the background.`}
    </p>}
    {progress && (progress.waiting > 0 || progress.sent > 0 || progress.failed > 0) && <p className="panel-sub" aria-live="polite" id="bulk-invite-progress">
      Academy invitation emails: {progress.waiting} waiting · {progress.sent} sent · {progress.failed} failed (last 14 days)
      {progress.failed > 0 && ". Failed students can be emailed again from here."}
    </p>}
  </form>;
}
