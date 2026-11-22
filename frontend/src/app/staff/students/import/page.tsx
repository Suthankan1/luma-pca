import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { StudentImportForm } from "@/components/staff/StudentImportForm";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, batchOptions, type CourseRef } from "@/lib/staff-forms";

type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export const metadata = { title: "Import students · LUMA" };

export default async function ImportStudentsPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "students" as const, crumb: "People / Students", title: "Import students",
    subtitle: "Add many students from a spreadsheet. Every row is checked first; nothing is saved unless the whole file is valid.",
    hasStudentPortal: hasPortal(identity, "student") };
  const [courses, batches] = await Promise.all([staffApi<CourseRef[]>("/api/v1/courses"), staffApi<Batch[]>("/api/v1/batches")]);
  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="import-title">
      <h2 id="import-title" className="panel-title">IMPORT FROM CSV</h2>
      {!courses.ok || !batches.ok
        ? <p className="session-error" role="alert">We couldn’t load batches right now. Please reload the page shortly.</p>
        : <StudentImportForm batches={batchOptions(courses.data, batches.data, batch => batch.active)} today={academyToday()} />}
      <p><Link className="text-link" href="/staff/students/manage">← Back to student management</Link></p>
    </section>
  </StaffShell>;
}
