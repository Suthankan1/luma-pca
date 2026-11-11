"use client";

import Link from "next/link";
import { useActionState } from "react";
import { assignStaff, changeRoles, createStaff, disableStaff, unassignStaff } from "@/app/staff/team/actions";
import { staffRoleLabels, type FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

function RoleChoices({ roles, checked, legend }: { roles: string[]; checked: string[]; legend: string }) {
  return <fieldset className="role-choices">
    <legend>{legend}</legend>
    {roles.map(role => <label key={role} className="check-row">
      <input type="checkbox" name="roles" value={role} defaultChecked={checked.includes(role)} /> {staffRoleLabels[role]}
    </label>)}
  </fieldset>;
}

export function AddStaffForm({ grantable }: { grantable: string[] }) {
  const [state, action, pending] = useActionState(createStaff, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="staff-feedback">
    <h3 className="form-title">Add a staff member</h3>
    <label htmlFor="staff-email">Email</label>
    <input id="staff-email" name="email" type="email" maxLength={254} required autoComplete="off" defaultValue={values?.email} />
    <RoleChoices roles={grantable} checked={values?.roles ? values.roles.split(",") : []} legend="Roles" />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Add staff member"}</button>
    <Feedback state={state} id="staff-feedback" />
    {state?.ok && state.values?.link && <p><Link className="text-link" href={state.values.link}>Open their record →</Link></p>}
  </form>;
}

export function RolesForm({ id, version, roles, grantable }: { id: string; version: number; roles: string[]; grantable: string[] }) {
  const [state, action, pending] = useActionState(changeRoles, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="roles-feedback">
    <input type="hidden" name="id" value={id} />
    <input type="hidden" name="version" value={version} />
    <RoleChoices roles={grantable} checked={roles} legend="Roles" />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save roles"}</button>
    <Feedback state={state} id="roles-feedback" />
  </form>;
}

export function DisableForm({ id, email }: { id: string; email: string }) {
  const [state, action, pending] = useActionState(disableStaff, null);
  return <form action={action} className="session-form staff-form danger-zone" aria-describedby="disable-feedback">
    <h3 className="form-title">Disable account</h3>
    <input type="hidden" name="id" value={id} />
    <p className="panel-sub">Blocks sign-in and signs {email} out everywhere. There is no way to re-enable an account yet.</p>
    <label className="check-row"><input type="checkbox" name="confirm" value="yes" required /> I understand this cannot be undone yet</label>
    <button className="action danger" type="submit" disabled={pending}>{pending ? "Disabling…" : "Disable account"}</button>
    <Feedback state={state} id="disable-feedback" />
  </form>;
}

export function AssignForm({ batchId }: { batchId: string }) {
  const [state, action, pending] = useActionState(assignStaff, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="assign-feedback">
    <h3 className="form-title">Assign staff</h3>
    <input type="hidden" name="batchId" value={batchId} />
    <label htmlFor="assign-email">Staff email</label>
    <input id="assign-email" name="email" type="email" maxLength={254} required autoComplete="off" defaultValue={values?.email} />
    <label htmlFor="assign-role">Role in this batch</label>
    <select id="assign-role" name="role" required defaultValue={values?.role ?? "INSTRUCTOR"}>
      <option value="INSTRUCTOR">Instructor</option>
      <option value="ATTENDANCE_OFFICER">Attendance officer</option>
    </select>
    <p className="panel-sub">The person must already hold that role in the academy.</p>
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Assign"}</button>
    <Feedback state={state} id="assign-feedback" />
  </form>;
}

export function UnassignButton({ id, batchId, label }: { id: string; batchId: string; label: string }) {
  const [state, action, pending] = useActionState(unassignStaff, null);
  return <form action={action} className="inline-form">
    <input type="hidden" name="id" value={id} />
    <input type="hidden" name="batchId" value={batchId} />
    <button className="link-button" type="submit" disabled={pending} aria-label={`Remove ${label}`}>{pending ? "…" : "Remove"}</button>
    {state && !state.ok && <span className="inline-error" role="alert">{state.message}</span>}
  </form>;
}
