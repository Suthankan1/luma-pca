import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { isAcademyManager } from "@/lib/staff-forms";

export default async function StaffPage() {
  const identity = await requirePortal("staff");
  const manager = isAcademyManager(identity.roles);
  return <StaffShell active="overview" crumb="Operations" title="Staff portal"
    subtitle="You’re signed in to Physics Cube Academy." hasStudentPortal={hasPortal(identity, "student")}>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="setup-title">
        <h2 id="setup-title" className="panel-title">ACADEMIC SETUP</h2>
        <p className="panel-sub">{manager ? "Courses and the batches that take them." : "Owners and administrators set up courses and batches."}</p>
        <div className="nav-links"><Link className="action" href="/staff/courses">Courses &amp; batches →</Link></div>
      </section>
      <section className="panel" aria-labelledby="soon-title">
        <h2 id="soon-title" className="panel-title">COMING NEXT</h2>
        <p className="panel-sub">Students and enrollments, staff and invitations, class sessions and the Zoom review queue are available through the API and will get screens next. Attendance appears once Zoom participant data is verified.</p>
      </section>
    </div>
  </StaffShell>;
}
