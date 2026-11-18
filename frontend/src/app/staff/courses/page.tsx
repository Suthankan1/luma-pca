import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { ActiveToggle, BatchForm, CourseForm } from "@/components/staff/ActionForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { formatDate, isAcademyManager } from "@/lib/staff-forms";

type Course = { id: string; code: string; title: string; active: boolean; version: number };
type Batch = { id: string; courseId: string; code: string; name: string; startsOn: string; endsOn: string | null; active: boolean; version: number };

export const metadata = { title: "Courses & batches · LUMA" };

export default async function CoursesPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "courses" as const, crumb: "Academic setup", title: "Courses & batches",
    subtitle: "Every batch belongs to one course. Records are deactivated, never deleted, so attendance history keeps its context.",
    hasStudentPortal: hasPortal(identity, "student") };

  if (!isAcademyManager(identity.roles)) {
    // Instructors and attendance officers: the API returns only the batches they are assigned to.
    const mine = await staffApi<Batch[]>("/api/v1/batches");
    return <StaffShell {...shell}>
      <p className="banner">Only academy owners and administrators manage courses and batches. Ask an administrator if something here needs to change.</p>
      {mine.ok && <section className="panel" aria-labelledby="mine-title">
        <h2 id="mine-title" className="panel-title">YOUR BATCHES</h2>
        <p className="panel-sub">{mine.data.length === 0 ? "You are not assigned to any batch yet." : "Batches you are assigned to."}</p>
        <ul className="dir-list">{mine.data.map(batch => <li key={batch.id} className="dir-row dir-course">
          <div><strong>{batch.code}</strong> <span>{batch.name}</span></div>
          <Link className="text-link" href={`/staff/batches/${batch.id}`}>Roster →</Link>
        </li>)}</ul>
      </section>}
    </StaffShell>;
  }

  const [courses, batches] = await Promise.all([staffApi<Course[]>("/api/v1/courses"), staffApi<Batch[]>("/api/v1/batches")]);
  if (!courses.ok || !batches.ok) {
    return <StaffShell {...shell}>
      <p className="session-error" role="alert">We couldn’t load courses and batches right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const byCourse = new Map<string, Batch[]>();
  for (const batch of batches.data) byCourse.set(batch.courseId, [...(byCourse.get(batch.courseId) ?? []), batch]);
  const activeCourses = courses.data.filter(course => course.active);

  return <StaffShell {...shell}>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="courses-title">
        <h2 id="courses-title" className="panel-title">COURSES</h2>
        <p className="panel-sub">{courses.data.length === 0 ? "No courses yet. Create the first one to start adding batches."
          : `${courses.data.length} course${courses.data.length === 1 ? "" : "s"} · ${batches.data.length} batch${batches.data.length === 1 ? "" : "es"}`}</p>
        <ul className="dir-list">
          {courses.data.map(course => <li key={course.id} className="dir-course">
            <div className="dir-row">
              <div><strong>{course.code}</strong> <span>{course.title}</span></div>
              <div className="dir-meta">
                <span className={`status ${course.active ? "on" : "off"}`}>{course.active ? "Active" : "Inactive"}</span>
                <ActiveToggle kind="course" id={course.id} version={course.version} active={course.active} label={`course ${course.code}`} />
              </div>
            </div>
            <ul className="dir-batches" aria-label={`Batches of ${course.code}`}>
              {(byCourse.get(course.id) ?? []).map(batch => <li key={batch.id} className="dir-row dir-batch">
                <div><Link className="text-link" href={`/staff/batches/${batch.id}`}><strong>{batch.code}</strong> <span>{batch.name}</span></Link>
                  <span className="muted dir-dates"> · {formatDate(batch.startsOn)}{batch.endsOn ? ` – ${formatDate(batch.endsOn)}` : " onwards"}</span></div>
                <div className="dir-meta">
                  <span className={`status ${batch.active ? "on" : "off"}`}>{batch.active ? "Active" : "Inactive"}</span>
                  <ActiveToggle kind="batch" id={batch.id} version={batch.version} active={batch.active} label={`batch ${batch.code}`} />
                </div>
              </li>)}
              {(byCourse.get(course.id) ?? []).length === 0 && <li className="dir-empty muted">No batches yet.</li>}
            </ul>
          </li>)}
        </ul>
      </section>
      <section className="panel" aria-labelledby="add-title">
        <h2 id="add-title" className="panel-title">ADD</h2>
        <p className="panel-sub academy-hint">Academy classes get their batch automatically under{" "}
          <Link className="text-link" href="/staff/academy">Academy classes →</Link></p>
        <p className="panel-sub">Codes are stored in capitals and must be unique.</p>
        <CourseForm />
        <BatchForm courses={activeCourses.map(({ id, code, title }) => ({ id, code, title }))} />
      </section>
    </div>
  </StaffShell>;
}
