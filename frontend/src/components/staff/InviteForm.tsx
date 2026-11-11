"use client";

import { useActionState, useState } from "react";
import { issueInvitation } from "@/app/staff/invitations/actions";

export function InviteForm({ accountId, who }: { accountId: string; who: "student" | "staff member" }) {
  const [state, action, pending] = useActionState(issueInvitation, null);
  const [copied, setCopied] = useState(false);
  const link = state?.ok ? state.values?.link : undefined;
  return <form action={action} className="session-form staff-form" aria-describedby="invite-feedback">
    <h3 className="form-title">Invitation</h3>
    <input type="hidden" name="accountId" value={accountId} />
    <p className="panel-sub">The {who} opens the link and chooses a password. Creating a new link cancels the previous one.</p>
    {link ? <div className="invite-box">
      <p className="form-ok" role="status">Invitation created. This link is shown only now; send it to the {who} privately.</p>
      <label htmlFor="invite-link">Invitation link</label>
      <input id="invite-link" readOnly value={link} onFocus={event => event.currentTarget.select()} />
      <p className="panel-sub">Expires {new Date(state!.values!.expiresAt).toLocaleString("en-GB", { timeZone: "Asia/Colombo", dateStyle: "medium", timeStyle: "short" })} (Colombo time).</p>
      <button type="button" className="action secondary" onClick={async () => {
        try { await navigator.clipboard.writeText(link); setCopied(true); } catch { setCopied(false); }
      }}>{copied ? "Copied" : "Copy link"}</button>
    </div> : <button className="action" type="submit" disabled={pending}>{pending ? "Creating…" : "Create invitation link"}</button>}
    {state && !state.ok && <p id="invite-feedback" className="session-error" role="alert">{state.message}</p>}
  </form>;
}
