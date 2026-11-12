import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { InviteForm } from "@/components/staff/InviteForm";
import { EnrollForm, EnrollmentAction, StudentDetailsForm } from "@/components/staff/StudentForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, accountStatusLabels, formatDate, isAcademyManager, uuidOrNull } from "@/lib/staff-forms";

type Student = { id: string; studentNumber: string; fullName: string; email: string | null; accountId: string;
  accountStatus: string | null; active: boolean; version: number };
type Enrollment = { id: string; batchId: string; batchCode: string; batchName: string; enrolledOn: string;
  withdrawnOn: string | null; status: string; version: number };
type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };
type Course = { id: string; code: string };

export const metadata = { title: "Student · LUMA" };

export default async function StudentPage({ params }: { params: Promise<{ studentId: string }> }) {
  const identity = await requirePortal("staff");
  const studentId = uuidOrNull((await params).studentId);
  if (!studentId) notFound();
  const shellBase = { active: "students" as const, crumb: "Students", hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shellBase} title="Student" subtitle="">
      <p className="banner">Only academy owners and administrators manage student records.</p>
    </StaffShell>;
  }

  const [student, enrollments, batches, courses] = await Promise.all([
    staffApi<Student>(`/api/v1/students/${studentId}`),
    staffApi<Enrollment[]>(`/api/v1/students/${studentId}/enrollments`),
    staffApi<Batch[]>("/api/v1/batches"),
    staffApi<Course[]>("/api/v1/courses"),
  ]);
  if (!student.ok && student.status === 404) notFound();
  if (!student.ok || !enrollments.ok || !batches.ok || !courses.ok) {
    return <StaffShell {...shellBase} title="Student" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this student right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const s = student.data;
  const today = academyToday();
  const courseCode = new Map(courses.data.map(c => [c.id, c.code]));
  const taken = new Set(enrollments.data.map(e => e.batchId));
  const options = batches.data.filter(b => b.active && !taken.has(b.id))
    .map(b => ({ id: b.id, label: `${courseCode.get(b.courseId) ?? ""} · ${b.code} · ${b.name}` }));

  return <StaffShell {...shellBase} title={`${s.studentNumber} ${s.fullName}`}
    subtitle={`${s.email ?? ""} · ${s.accountStatus ? accountStatusLabels[s.accountStatus] ?? s.accountStatus : ""}${s.active ? "" : " · Inactive student"}`}>
    <p><Link className="text-link" href="/staff/students">← All students</Link></p>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="enrollments-title">
        <h2 id="enrollments-title" className="panel-title">ENROLMENTS</h2>
        <p className="panel-sub">{enrollments.data.length === 0 ? "Not enrolled in any batch yet." : "Withdrawn enrolments stay on record so history keeps its context."}</p>
        <ul className="dir-list">
          {enrollments.data.map(e => <li key={e.id} className="dir-row dir-course">
            <div><Link className="text-link" href={`/staff/batches/${e.batchId}`}><strong>{e.batchCode}</strong> <span>{e.batchName}</span></Link>
              <span className="muted dir-dates"> · from {formatDate(e.enrolledOn)}{e.withdrawnOn ? ` · withdrawn ${formatDate(e.withdrawnOn)}` : ""}</span></div>
            <div className="dir-meta">
              <span className={`status ${e.withdrawnOn ? "off" : "on"}`}>{e.withdrawnOn ? "Withdrawn" : "Enrolled"}</span>
              <EnrollmentAction studentId={s.id} today={today} enrollment={e} />
            </div>
          </li>)}
        </ul>
        {s.active ? <EnrollForm studentId={s.id} batches={options} today={today} />
          : <p className="panel-sub">Reactivate the student to enrol them in a batch.</p>}
      </section>
      <section className="panel" aria-labelledby="manage-title">
        <h2 id="manage-title" className="panel-title">MANAGE</h2>
        {s.accountStatus === "PENDING_ACTIVATION"
          ? <InviteForm accountId={s.accountId} who="student" email={s.email ?? "the student’s address"} />
          : <p className="panel-sub">{s.accountStatus === "ACTIVE" ? "The student has activated their sign-in." : "The student’s sign-in is disabled."}</p>}
        <StudentDetailsForm id={s.id} version={s.version} fullName={s.fullName} active={s.active} />
      </section>
    </div>
  </StaffShell>;
}
