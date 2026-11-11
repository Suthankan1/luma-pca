import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { AddStaffForm } from "@/components/staff/TeamForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { accountStatusLabels, grantableRoles, isAcademyManager, roleList } from "@/lib/staff-forms";

type StaffAccount = { id: string; email: string; roles: string[]; status: string; mfaEnrolled: boolean };

export const metadata = { title: "Staff · LUMA" };

export default async function TeamPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "team" as const, crumb: "Staff", title: "Staff",
    subtitle: "Who can sign in to the staff portal, and with which roles. Every change here is recorded in the audit trail.",
    hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}><p className="banner">Only academy owners and administrators manage staff accounts.</p></StaffShell>;
  }
  const staff = await staffApi<StaffAccount[]>("/api/v1/staff-accounts");
  return <StaffShell {...shell}>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="team-title">
        <h2 id="team-title" className="panel-title">STAFF ACCOUNTS</h2>
        {!staff.ok ? <p className="session-error" role="alert">We couldn’t load staff accounts right now. Please reload the page shortly.</p> : <>
          <p className="panel-sub">{staff.data.length} account{staff.data.length === 1 ? "" : "s"}. Students are managed under Students.</p>
          <ul className="dir-list">
            {staff.data.map(person => <li key={person.id} className="dir-row dir-course">
              <div><Link className="text-link" href={`/staff/team/${person.id}`}><strong>{person.email}</strong></Link>
                {person.id === identity.accountId && <span className="muted"> (you)</span>}
                <span className="muted dir-dates"> · {roleList(person.roles)}</span></div>
              <div className="dir-meta">
                <span className={`status ${person.status === "ACTIVE" ? "on" : "off"}`}>{accountStatusLabels[person.status] ?? person.status}</span>
                {person.status === "ACTIVE" && <span className="status">{person.mfaEnrolled ? "2-step on" : "2-step off"}</span>}
              </div>
            </li>)}
          </ul>
        </>}
      </section>
      <section className="panel" aria-labelledby="add-staff-title">
        <h2 id="add-staff-title" className="panel-title">ADD</h2>
        <p className="panel-sub">{identity.roles.includes("ACADEMY_OWNER") ? "Owners and administrators must use two-step sign-in."
          : "Only an owner can add owners or administrators."}</p>
        <AddStaffForm grantable={grantableRoles(identity.roles)} />
      </section>
    </div>
  </StaffShell>;
}
