import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { InviteForm } from "@/components/staff/InviteForm";
import { DisableForm, EnableForm } from "@/components/staff/TeamForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { accountStatusLabels, canManageStaff, uuidOrNull } from "@/lib/staff-forms";

type StaffAccount = { id: string; email: string; roles: string[]; status: string; version: number; passwordSet?: boolean };

export const metadata = { title: "Staff member · LUMA" };

export default async function StaffMemberPage({ params }: { params: Promise<{ accountId: string }> }) {
  const identity = await requirePortal("staff");
  const accountId = uuidOrNull((await params).accountId);
  if (!accountId) notFound();
  const base = { active: "team" as const, crumb: "Staff", hasStudentPortal: hasPortal(identity, "student") };
  const result = await staffApi<StaffAccount>(`/api/v1/staff-accounts/${accountId}`);
  if (!result.ok && result.status === 404) notFound();
  if (!result.ok) {
    return <StaffShell {...base} title="Staff member" subtitle=""><p className="session-error" role="alert">We couldn’t load this account right now. Please reload the page shortly.</p></StaffShell>;
  }
  const person = result.data;
  const manageable = canManageStaff(identity, person);
  return <StaffShell {...base} title={person.email} subtitle={`Staff · ${accountStatusLabels[person.status] ?? person.status}`}>
    <p><Link className="text-link" href="/staff/team">← All staff</Link></p>
    <section className="panel" aria-labelledby="account-title">
      <h2 id="account-title" className="panel-title">ACCOUNT</h2>
      {person.status === "PENDING_ACTIVATION" && manageable && <InviteForm accountId={person.id} who="staff member" email={person.email} />}
      {person.status !== "DISABLED" && manageable && <DisableForm id={person.id} email={person.email} />}
      {!manageable && <p className="panel-sub">You can’t disable your own account; ask another staff member.</p>}
      {person.status === "DISABLED" && <p className="panel-sub">Disabled accounts cannot sign in.</p>}
      {person.status === "DISABLED" && manageable && <EnableForm id={person.id} email={person.email} hadPassword={person.passwordSet === true} />}
      <p className="panel-sub"><Link className="text-link" href={`/staff/activity?target=${person.id}`}>See this account’s activity →</Link></p>
    </section>
  </StaffShell>;
}
