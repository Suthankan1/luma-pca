import { AcademyClassActions } from "./AcademyForms";
import { staffApi } from "@/lib/staff-api";
import { formatSyncedAt, type AcademyClass } from "@/lib/academy";
import type { WebinarMonitor } from "@/lib/webinars";

type Course = { id: string; code: string };
type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export async function readClassTypeRosters() {
  const [classes, courses, batches, monitors] = await Promise.all([
    staffApi<AcademyClass[]>("/api/v1/academy/classes", undefined, 25000),
    staffApi<Course[]>("/api/v1/courses"),
    staffApi<Batch[]>("/api/v1/batches"),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
  ]);

  const codes = new Map((courses.ok ? courses.data : []).map(course => [course.id, course.code]));
  const label = (batch: Omit<Batch, "active">) => [codes.get(batch.courseId), batch.code, batch.name].filter(Boolean).join(" · ");
  const assigned = new Set(classes.ok ? classes.data.flatMap(item => item.batch ? [item.batch.id] : []) : []);
  const available = (batches.ok ? batches.data : []).filter(batch => batch.active && !assigned.has(batch.id))
    .map(batch => ({ id: batch.id, label: label(batch) }));

  return {
    classes: classes.ok ? classes.data : null,
    batchesFailed: !batches.ok,
    monitorClasses: new Map((monitors.ok ? monitors.data : []).map(m => [m.webinarId, m.academyClassName])),
    available,
    label,
  };
}

export function ClassTypeRosterControls({ item, slug, roster }: {
  item?: AcademyClass; slug: string; roster: Awaited<ReturnType<typeof readClassTypeRosters>>;
}) {
  if (!item) return <div className="class-type-roster-inline">
    <span className="status off">{roster.classes ? "Academy class not matched" : "Sync unavailable"}</span>
    <label className="academy-link" htmlFor={`unavailable-${slug}`}>Student batch
      <select id={`unavailable-${slug}`} disabled>
        <option>{roster.classes ? "Match the Academy class in Tracked Zoom links" : "Academy database unavailable"}</option>
      </select>
    </label>
  </div>;
  const { className, studentCount, batch } = item;
  const synced = !!batch?.lastSyncedAt && batch.lastSyncedStudents === studentCount;
  const status = !batch ? "Batch not selected" : !batch.active ? "Batch inactive" : synced ? "Synced" : "Sync needed";
  return <div className="class-type-roster-inline">
    <div>
      <span className={`status ${synced && batch?.active ? "on" : "off"}`}>{status}</span>
      <p className="panel-sub">{studentCount} Academy {studentCount === 1 ? "student" : "students"}
        {batch?.lastSyncedAt && ` · Last synced ${formatSyncedAt(batch.lastSyncedAt)}`}</p>
    </div>
    <AcademyClassActions className={className} slug={slug} linked={!!batch} batches={roster.available} rosterControls
      selectedBatch={batch ? { id: batch.id, label: roster.label(batch) } : undefined} />
  </div>;
}
