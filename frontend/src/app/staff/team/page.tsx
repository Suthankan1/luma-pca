import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { AddStaffForm } from "@/components/staff/TeamForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { accountStatusLabels } from "@/lib/staff-forms";

type StaffAccount = { id: string; email: string; roles: string[]; status: string };

export const metadata = { title: "Staff · LUMA" };

export default async function TeamPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "team" as const, crumb: "Staff", title: "Staff",
    subtitle: "Who can sign in to the staff portal. Every staff member can do everything here; every change is recorded in the audit trail.",
    hasStudentPortal: hasPortal(identity, "student") };
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
                {person.id === identity.accountId && <span className="muted"> (you)</span>}</div>
              <div className="dir-meta">
                <span className={`status ${person.status === "ACTIVE" ? "on" : "off"}`}>{accountStatusLabels[person.status] ?? person.status}</span>
              </div>
            </li>)}
          </ul>
        </>}
      </section>
      <section className="panel" aria-labelledby="add-staff-title">
        <h2 id="add-staff-title" className="panel-title">ADD</h2>
        <p className="panel-sub">New staff get full staff access and sign in with their email and password.</p>
        <AddStaffForm />
      </section>
    </div>
  </StaffShell>;
}
