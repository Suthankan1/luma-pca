import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { InviteForm } from "@/components/staff/InviteForm";
import { DisableForm, EnableForm, RolesForm } from "@/components/staff/TeamForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { accountStatusLabels, canManageStaff, grantableRoles, isAcademyManager, roleList, uuidOrNull } from "@/lib/staff-forms";

type StaffAccount = { id: string; email: string; roles: string[]; status: string; mfaEnrolled: boolean; version: number; passwordSet?: boolean };

export const metadata = { title: "Staff member · LUMA" };

export default async function StaffMemberPage({ params }: { params: Promise<{ accountId: string }> }) {
  const identity = await requirePortal("staff");
  const accountId = uuidOrNull((await params).accountId);
  if (!accountId) notFound();
  const base = { active: "team" as const, crumb: "Staff", hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...base} title="Staff member" subtitle=""><p className="banner">Only academy owners and administrators manage staff accounts.</p></StaffShell>;
  }
  const result = await staffApi<StaffAccount>(`/api/v1/staff-accounts/${accountId}`);
  if (!result.ok && result.status === 404) notFound();
  if (!result.ok) {
    return <StaffShell {...base} title="Staff member" subtitle=""><p className="session-error" role="alert">We couldn’t load this account right now. Please reload the page shortly.</p></StaffShell>;
  }
  const person = result.data;
  const self = person.id === identity.accountId;
  const manageable = canManageStaff(identity, person);
  return <StaffShell {...base} title={person.email}
    subtitle={`${roleList(person.roles)} · ${accountStatusLabels[person.status] ?? person.status}${person.status === "ACTIVE" ? (person.mfaEnrolled ? " · 2-step sign-in on" : " · 2-step sign-in off") : ""}`}>
    <p><Link className="text-link" href="/staff/team">← All staff</Link></p>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="roles-title">
        <h2 id="roles-title" className="panel-title">ROLES</h2>
        {self ? <p className="panel-sub">You can’t change your own roles; ask another owner.</p>
          : !manageable ? <p className="panel-sub">Only an owner can change an owner’s or administrator’s roles.</p>
          : person.status === "DISABLED" ? <p className="panel-sub">This account is disabled.</p>
          : <RolesForm id={person.id} version={person.version} roles={person.roles} grantable={grantableRoles(identity.roles)} />}
      </section>
      <section className="panel" aria-labelledby="account-title">
        <h2 id="account-title" className="panel-title">ACCOUNT</h2>
        {person.status === "PENDING_ACTIVATION" && manageable && <InviteForm accountId={person.id} who="staff member" email={person.email} />}
        {person.status !== "DISABLED" && manageable && <DisableForm id={person.id} email={person.email} />}
        {(self || !manageable) && <p className="panel-sub">{self ? "You can’t disable your own account." : "Only an owner can manage this account."}</p>}
        {person.status === "DISABLED" && <p className="panel-sub">Disabled accounts cannot sign in.</p>}
        {person.status === "DISABLED" && manageable && <EnableForm id={person.id} email={person.email} hadPassword={person.passwordSet === true} />}
        <p className="panel-sub"><Link className="text-link" href={`/staff/activity?target=${person.id}`}>See this account’s activity →</Link></p>
      </section>
    </div>
  </StaffShell>;
}
