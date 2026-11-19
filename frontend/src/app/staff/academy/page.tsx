import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { AcademyClassActions } from "@/components/staff/AcademyForms";
import { requirePortal } from "@/lib/auth-server";
import { formatSyncedAt, type AcademyClass } from "@/lib/academy";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { isAcademyManager } from "@/lib/staff-forms";

type Course = { id: string; code: string };
type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export const metadata = { title: "Academy classes · LUMA" };

export default async function AcademyClassesPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "academy" as const, crumb: "Academy database", title: "Academy classes",
    subtitle: "Classes and students come from the Academy’s own records (read-only). Set each class up once; sync whenever students join.",
    hasStudentPortal: hasPortal(identity, "student") };

  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}>
      <p className="banner">Only academy owners and administrators connect Academy classes. Your batches are under Courses & batches.</p>
    </StaffShell>;
  }

  const [classes, courses, batches] = await Promise.all([staffApi<AcademyClass[]>("/api/v1/academy/classes", undefined, 25000),
    staffApi<Course[]>("/api/v1/courses"), staffApi<Batch[]>("/api/v1/batches")]);
  if (!classes.ok) {
    return <StaffShell {...shell}>
      {classes.status === 503
        ? <p className="banner" role="status">{classes.detail ?? "The Academy database is not connected."} Classes appear here once the
          server’s Academy connection (LUMA_ACADEMY_PAYMENTS_…) is set up. Until then, manage courses, batches and students as before.</p>
        : <p className="session-error" role="alert">We couldn’t load the Academy classes right now. Please reload the page shortly.</p>}
    </StaffShell>;
  }

  const courseCode = new Map((courses.ok ? courses.data : []).map(c => [c.id, c.code]));
  const linkedIds = new Set(classes.data.flatMap(c => c.batch ? [c.batch.id] : []));
  const freeBatches = (batches.ok ? batches.data : []).filter(b => b.active && !linkedIds.has(b.id))
    .map(b => ({ id: b.id, label: `${courseCode.get(b.courseId) ?? "?"} · ${b.code} · ${b.name}` }));
  const linked = classes.data.filter(c => c.batch).length;

  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="classes-title">
      <h2 id="classes-title" className="panel-title">ACADEMY CLASSES</h2>
      <p className="panel-sub">{classes.data.length === 0 ? "The Academy records have no class payments yet."
        : `${classes.data.length} class${classes.data.length === 1 ? "" : "es"} · ${linked} set up in LUMA · student counts are distinct students, not payments`}</p>
      <ul className="dir-list academy-list">
        {classes.data.map((item, index) => {
          const slug = `c${index}`;
          return <li key={item.className} className="dir-course" aria-label={item.className}>
            <div className="dir-row">
              <div>
                <strong>{item.className}</strong>
                <span className="muted"> · {item.studentCount} student{item.studentCount === 1 ? "" : "s"}</span>
                {item.batch
                  ? <p className="panel-sub">Batch <Link className="text-link" href={`/staff/batches/${item.batch.id}`}>
                      {courseCode.get(item.batch.courseId) ?? ""} · {item.batch.code}</Link>
                      {" "}· last synced {formatSyncedAt(item.batch.lastSyncedAt)}
                      {item.batch.lastSyncedStudents !== null && ` (${item.batch.lastSyncedStudents} students)`}
                      {!item.batch.active && " · batch inactive"}</p>
                  : <p className="panel-sub">Not set up in LUMA yet.</p>}
              </div>
              <div className="dir-meta">
                <span className={`status ${item.batch ? "on" : "off"}`}>{item.batch ? "Set up" : "Not set up"}</span>
              </div>
            </div>
            <AcademyClassActions className={item.className} slug={slug} linked={!!item.batch} batches={freeBatches} />
            {item.batch && <p className="academy-next"><Link className="text-link" href={`/staff/sessions?batch=${item.batch.id}`}>
              Classes and webinars for this batch →</Link></p>}
          </li>;
        })}
      </ul>
    </section>
    <Disclosure summary="How Academy classes work" className="panel-gap">
      <ul className="panel-sub how-list">
        <li><strong>Set up in LUMA</strong> creates a batch named after the class (course ACADEMY) and enrols its students. Use an
          existing batch instead if the class already has one with history.</li>
        <li><strong>Sync students</strong> adds students who joined the Academy class since. Nobody is removed automatically: students
          who left are listed so you can decide on the batch roster.</li>
        <li>Then add the class’s Zoom webinar under <Link className="text-link" href="/staff/webinars">Webinars</Link> (its ID or link
          only). When the webinar’s Zoom title matches the Academy class, each run expects these students; otherwise choose the class
          there.</li>
        <li>LUMA never changes the Academy’s records and never reads its attendance table.</li>
      </ul>
    </Disclosure>
  </StaffShell>;
}
