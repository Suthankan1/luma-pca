import { AcademyClassActions } from "./AcademyForms";
import { staffApi } from "@/lib/staff-api";
import type { AcademyClass } from "@/lib/academy";
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
  const assigned = new Map(classes.ok ? classes.data.flatMap(item => item.batch ? [[item.batch.id, item.className] as const] : []) : []);
  const batchOptions = (batches.ok ? batches.data : []).map(batch => {
    const owner = assigned.get(batch.id);
    const reason = !batch.active ? "Inactive" : owner ? `Linked to ${owner}` : null;
    return { id: batch.id, label: reason ? `${label(batch)} (${reason})` : label(batch), disabled: !!reason };
  });

  return {
    classes: classes.ok ? classes.data : null,
    batchesFailed: !batches.ok,
    monitors: monitors.ok ? monitors.data : [],
    monitorsFailed: !monitors.ok,
    batchOptions,
    label,
  };
}

export function ClassTypeRosterControls({ item, slug, roster }: {
  item?: AcademyClass; slug: string; roster: Awaited<ReturnType<typeof readClassTypeRosters>>;
}) {
  if (!item) return <div className="class-type-roster-inline">
    <span className="status off">{roster.classes ? "Academy class not matched" : "Sync unavailable"}</span>
    <label className="academy-link">Student batch
      <select disabled defaultValue="">
        <option value="">{roster.classes ? "Match an Academy class first" : "Academy roster unavailable"}</option>
        {roster.batchOptions.map(batch => <option key={batch.id} value={batch.id}>{batch.label}</option>)}
      </select>
    </label>
  </div>;
  const { className, studentCount, batch } = item;
  const synced = !!batch?.active && !!batch.lastSyncedAt && batch.lastSyncedStudents === studentCount;
  return <div className="class-type-roster-inline">
    <span className={`status ${synced ? "on" : "off"}`}>{synced ? "Synced" : "Sync needed"}</span>
    <AcademyClassActions className={className} slug={slug} linked={!!batch}
      batches={roster.batchOptions.filter(option => !batch?.active || option.id !== batch.id)} rosterControls
      selectedBatch={batch?.active ? { id: batch.id, label: roster.label(batch) } : undefined} />
  </div>;
}
