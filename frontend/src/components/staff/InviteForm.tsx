"use client";

import { useActionState, useState } from "react";
import { issueInvitation } from "@/app/staff/invitations/actions";

const colomboTime = (iso: string) =>
  new Date(iso).toLocaleString("en-GB", { timeZone: "Asia/Colombo", dateStyle: "medium", timeStyle: "short" });

/** Email the invitation to the account's address, or create a link to send another way. */
export function InviteForm({ accountId, who, email }: { accountId: string; who: "student" | "staff member"; email: string }) {
  const [state, action, pending] = useActionState(issueInvitation, null);
  const [copied, setCopied] = useState(false);
  const link = state?.ok ? state.values?.link : undefined;
  const emailed = state?.ok && state.values?.delivery === "EMAIL";
  return <form action={action} className="session-form staff-form" aria-describedby="invite-feedback">
    <h3 className="form-title">Invitation</h3>
    <input type="hidden" name="accountId" value={accountId} />
    <p className="panel-sub">The {who} opens the invitation link and chooses a password. A new invitation cancels the previous one.</p>
    {link ? <div className="invite-box">
      <p className="form-ok" role="status">Invitation created. This link is shown only now; send it to the {who} privately.</p>
      <label htmlFor="invite-link">Invitation link</label>
      <input id="invite-link" readOnly value={link} onFocus={event => event.currentTarget.select()} />
      <p className="panel-sub">Expires {colomboTime(state!.values!.expiresAt)} (Colombo time).</p>
      <button type="button" className="action secondary" onClick={async () => {
        try { await navigator.clipboard.writeText(link); setCopied(true); } catch { setCopied(false); }
      }}>{copied ? "Copied" : "Copy link"}</button>
    </div> : <>
      {emailed && <p id="invite-feedback" className="form-ok" role="status">
        Invitation emailed to {email}. The link expires {colomboTime(state!.values!.expiresAt)} (Colombo time).</p>}
      <div className="button-row">
        <button className="action" type="submit" name="delivery" value="EMAIL" disabled={pending}>
          {pending ? "Working…" : emailed ? "Email it again" : "Email invitation"}</button>
        <button className="action secondary" type="submit" name="delivery" value="LINK" disabled={pending}>Create invitation link</button>
      </div>
    </>}
    {state && !state.ok && <p id="invite-feedback" className="session-error" role="alert">{state.message}</p>}
  </form>;
}
