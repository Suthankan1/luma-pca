"use client";

import Link from "next/link";
import { useActionState } from "react";
import { createStaff, disableStaff, enableStaff } from "@/app/staff/team/actions";
import { type FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

/** Every staff member has the same access (one STAFF role), so only the email is asked for. */
export function AddStaffForm() {
  const [state, action, pending] = useActionState(createStaff, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="staff-feedback">
    <h3 className="form-title">Add a staff member</h3>
    <label htmlFor="staff-email">Email</label>
    <input id="staff-email" name="email" type="email" maxLength={254} required autoComplete="off" defaultValue={values?.email} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Add staff member"}</button>
    <Feedback state={state} id="staff-feedback" />
    {state?.ok && state.values?.link && <p><Link className="text-link" href={state.values.link}>Open their record →</Link></p>}
  </form>;
}

export function DisableForm({ id, email }: { id: string; email: string }) {
  const [state, action, pending] = useActionState(disableStaff, null);
  return <form action={action} className="session-form staff-form danger-zone" aria-describedby="disable-feedback">
    <h3 className="form-title">Disable account</h3>
    <input type="hidden" name="id" value={id} />
    <p className="panel-sub">Blocks sign-in and signs {email} out everywhere. You can re-enable the account later.</p>
    <label className="check-row"><input type="checkbox" name="confirm" value="yes" required /> Sign this person out everywhere and block sign-in</label>
    <button className="action danger" type="submit" disabled={pending}>{pending ? "Disabling…" : "Disable account"}</button>
    <Feedback state={state} id="disable-feedback" />
  </form>;
}

/** Re-enabling keeps the old password unless a new one is required; earlier sessions stay ended either way. */
export function EnableForm({ id, email, hadPassword }: { id: string; email: string; hadPassword: boolean }) {
  const [state, action, pending] = useActionState(enableStaff, null);
  return <form action={action} className="session-form staff-form" aria-describedby="enable-feedback">
    <h3 className="form-title">Re-enable account</h3>
    <input type="hidden" name="id" value={id} />
    <p className="panel-sub">{hadPassword
      ? `${email} can sign in again with their existing password. Sessions ended when the account was disabled stay ended.`
      : `${email} never set a password, so they will need a new invitation.`}</p>
    {hadPassword && <label className="check-row"><input type="checkbox" name="requireNewPassword" value="yes" />
      Make them choose a new password (send a new invitation afterwards)</label>}
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Re-enable account"}</button>
    <Feedback state={state} id="enable-feedback" />
  </form>;
}
