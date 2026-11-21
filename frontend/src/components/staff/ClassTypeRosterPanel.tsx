import { AcademyClassActions } from "./AcademyForms";
import { PanelRetry } from "./PanelRetry";
import { staffApi } from "@/lib/staff-api";
import { formatSyncedAt, type AcademyClass } from "@/lib/academy";

type Course = { id: string; code: string };
type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export async function ClassTypeRosterPanel() {
  const [classes, courses, batches] = await Promise.all([
    staffApi<AcademyClass[]>("/api/v1/academy/classes", undefined, 25000),
    staffApi<Course[]>("/api/v1/courses"),
    staffApi<Batch[]>("/api/v1/batches"),
  ]);

  const codes = new Map((courses.ok ? courses.data : []).map(course => [course.id, course.code]));
  const label = (batch: Omit<Batch, "active">) => [codes.get(batch.courseId), batch.code, batch.name].filter(Boolean).join(" · ");
  const assigned = new Set(classes.ok ? classes.data.flatMap(item => item.batch ? [item.batch.id] : []) : []);
  const available = (batches.ok ? batches.data : []).filter(batch => batch.active && !assigned.has(batch.id))
    .map(batch => ({ id: batch.id, label: label(batch) }));

  return <section className="panel panel-gap" aria-labelledby="class-rosters">
    <h2 className="panel-title" id="class-rosters">Student batch and sync</h2>
    {!classes.ok ? <p className="panel-sub" role="status">Academy class types couldn’t load. <PanelRetry /></p>
      : classes.data.length === 0 ? <p className="panel-sub">No Academy class types are available.</p>
        : <>
          <p className="panel-sub">Sync status uses the last successful sync and the current Academy student count.</p>
          {!batches.ok && <p className="session-error" role="alert">The batch list couldn’t load. <PanelRetry /></p>}
          <ul className="dir-list class-type-roster-list">
            {classes.data.map(({ className, studentCount, batch }, index) => {
              const synced = !!batch?.lastSyncedAt && batch.lastSyncedStudents === studentCount;
              const status = !batch ? "Batch not selected" : !batch.active ? "Batch inactive" : synced ? "Synced" : "Sync needed";
              return <li className="dir-course class-type-roster-row" key={className} aria-label={className}>
                <div>
                  <h3 className="form-title">{className}</h3>
                  <p className="panel-sub">{studentCount} Academy {studentCount === 1 ? "student" : "students"}</p>
                  <span className={`status ${synced && batch?.active ? "on" : "off"}`}>{status}</span>
                  {batch?.lastSyncedAt && <p className="panel-sub">Last synced {formatSyncedAt(batch.lastSyncedAt)}</p>}
                </div>
                <AcademyClassActions className={className} slug={`class-type-${index}`} linked={!!batch} batches={available} rosterControls
                  selectedBatch={batch ? { id: batch.id, label: label(batch) } : undefined} />
              </li>;
            })}
          </ul>
        </>}
  </section>;
}
