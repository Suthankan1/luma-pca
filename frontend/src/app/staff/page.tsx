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
        <p className="panel-sub">{manager ? "Courses, batches, the classes they take, students and staff."
          : "Your batches’ rosters and classes. Owners and administrators manage everything else."}</p>
        <div className="nav-links">
          <Link className="action" href="/staff/courses">Courses &amp; batches →</Link>
          <Link className="action secondary" href="/staff/sessions">Classes →</Link>
          {manager && <Link className="action secondary" href="/staff/zoom">Zoom review →</Link>}
        </div>
      </section>
      <section className="panel" aria-labelledby="soon-title">
        <h2 id="soon-title" className="panel-title">COMING NEXT</h2>
        <p className="panel-sub">Attendance. Zoom webinar events are already received and linked to classes; turning them into attendance waits until participant data is checked against a real test webinar.</p>
      </section>
    </div>
  </StaffShell>;
}
