import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { AssignForm, UnassignButton } from "@/components/staff/TeamForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { formatDate, isAcademyManager, staffRoleLabels, uuidOrNull } from "@/lib/staff-forms";

type Batch = { id: string; code: string; name: string; startsOn: string; endsOn: string | null; active: boolean };
type Assignment = { id: string; email: string | null; role: string };
type Enrollment = { id: string; studentId: string; studentNumber: string; studentName: string; enrolledOn: string; withdrawnOn: string | null };

export const metadata = { title: "Batch roster · LUMA" };

/** Readable by owners and administrators, and by instructors and attendance officers assigned to the batch. */
export default async function RosterPage({ params }: { params: Promise<{ batchId: string }> }) {
  const identity = await requirePortal("staff");
  const batchId = uuidOrNull((await params).batchId);
  if (!batchId) notFound();
  const [batch, roster] = await Promise.all([
    staffApi<Batch>(`/api/v1/batches/${batchId}`),
    staffApi<Enrollment[]>(`/api/v1/batches/${batchId}/enrollments`),
  ]);
  // The API answers 404 for batches outside the caller's academy or assignments, and 403 for roles with no batch access.
  if (!batch.ok && (batch.status === 404 || batch.status === 403)) notFound();
  const manager = isAcademyManager(identity.roles);
  const assignments = manager ? await staffApi<Assignment[]>(`/api/v1/batches/${batchId}/assignments`) : null;
  const shell = { active: "courses" as const, crumb: "Academic setup", hasStudentPortal: hasPortal(identity, "student") };
  if (!batch.ok || !roster.ok) {
    return <StaffShell {...shell} title="Batch roster" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this roster right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const b = batch.data;
  const current = roster.data.filter(e => !e.withdrawnOn);
  return <StaffShell {...shell} title={`${b.code} ${b.name}`}
    subtitle={`${formatDate(b.startsOn)}${b.endsOn ? ` – ${formatDate(b.endsOn)}` : " onwards"}${b.active ? "" : " · Inactive batch"}`}>
    <p><Link className="text-link" href="/staff/courses">← Courses &amp; batches</Link></p>
    <section className="panel" aria-labelledby="roster-title">
      <h2 id="roster-title" className="panel-title">ROSTER</h2>
      <p className="panel-sub">{current.length} enrolled{roster.data.length > current.length ? ` · ${roster.data.length - current.length} withdrawn` : ""}</p>
      <ul className="dir-list">
        {roster.data.map(e => <li key={e.id} className="dir-row dir-course">
          <div>{manager ? <Link className="text-link" href={`/staff/students/${e.studentId}`}><strong>{e.studentNumber}</strong> <span>{e.studentName}</span></Link>
            : <><strong>{e.studentNumber}</strong> <span>{e.studentName}</span></>}</div>
          <span className={`status ${e.withdrawnOn ? "off" : "on"}`}>{e.withdrawnOn ? `Withdrawn ${formatDate(e.withdrawnOn)}` : `Since ${formatDate(e.enrolledOn)}`}</span>
        </li>)}
        {roster.data.length === 0 && <li className="dir-empty muted">No students enrolled yet.</li>}
      </ul>
    </section>
    {assignments && <section className="panel panel-gap" aria-labelledby="assigned-title">
      <h2 id="assigned-title" className="panel-title">ASSIGNED STAFF</h2>
      {!assignments.ok ? <p className="session-error" role="alert">We couldn’t load assignments right now.</p> : <>
        <p className="panel-sub">{assignments.data.length === 0 ? "No one is assigned yet. Assigned instructors and attendance officers can open this roster."
          : "These people can open this roster."}</p>
        <ul className="dir-list">{assignments.data.map(a => <li key={a.id} className="dir-row dir-course">
          <div><strong>{a.email}</strong> <span className="muted">{staffRoleLabels[a.role] ?? a.role}</span></div>
          <UnassignButton id={a.id} batchId={batchId} label={`${a.email} as ${staffRoleLabels[a.role] ?? a.role}`} />
        </li>)}</ul>
        <AssignForm batchId={batchId} />
      </>}
    </section>}
  </StaffShell>;
}
