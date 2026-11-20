"use client";

import Link from "next/link";
import { useActionState } from "react";
import { createStudent, enroll, eraseStudent, updateEnrollment, updateStudent } from "@/app/staff/students/actions";
import type { FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

export function AddStudentForm() {
  const [state, action, pending] = useActionState(createStudent, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="student-feedback">
    <h3 className="form-title">Add a student</h3>
    <label htmlFor="student-number">Student number</label>
    <input id="student-number" name="studentNumber" maxLength={60} required autoCapitalize="characters" placeholder="PCA-2027-001"
      defaultValue={values?.studentNumber} />
    <label htmlFor="student-name">Full name</label>
    <input id="student-name" name="fullName" maxLength={200} required autoComplete="off" defaultValue={values?.fullName} />
    <label htmlFor="student-email">Email</label>
    <input id="student-email" name="email" type="email" maxLength={254} required autoComplete="off" defaultValue={values?.email} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Add student"}</button>
    <Feedback state={state} id="student-feedback" />
    {state?.ok && state.values?.link && <p><Link className="text-link" href={state.values.link}>Open the new student’s record →</Link></p>}
  </form>;
}

export function StudentDetailsForm({ id, version, fullName, active }: { id: string; version: number; fullName: string; active: boolean }) {
  const [state, action, pending] = useActionState(updateStudent, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="details-feedback">
    <h3 className="form-title">Details</h3>
    <input type="hidden" name="id" value={id} />
    <input type="hidden" name="version" value={version} />
    <label htmlFor="details-name">Full name</label>
    <input id="details-name" name="fullName" maxLength={200} required defaultValue={state && !state.ok ? state.values?.fullName : fullName} />
    <label className="check-row"><input type="checkbox" name="active" value="true" defaultChecked={active} /> Active student</label>
    <p className="panel-sub">An inactive student cannot be enrolled in new batches but can still sign in and see their own history.</p>
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save details"}</button>
    <Feedback state={state} id="details-feedback" />
  </form>;
}

export function EnrollForm({ studentId, batches, today }: {
  studentId: string; batches: { id: string; label: string }[]; today: string;
}) {
  const [state, action, pending] = useActionState(enroll, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="enroll-feedback">
    <h3 className="form-title">Enrol in a batch</h3>
    <input type="hidden" name="studentId" value={studentId} />
    <label htmlFor="enroll-batch">Batch</label>
    <select id="enroll-batch" name="batchId" required defaultValue={values?.batchId ?? ""}>
      <option value="" disabled>Choose a batch</option>
      {batches.map(batch => <option key={batch.id} value={batch.id}>{batch.label}</option>)}
    </select>
    <label htmlFor="enroll-date">Enrolled on</label>
    <input id="enroll-date" name="enrolledOn" type="date" required defaultValue={values?.enrolledOn ?? today} />
    <button className="action" type="submit" disabled={pending || batches.length === 0}>{pending ? "Saving…" : "Enrol"}</button>
    {batches.length === 0 && <p className="panel-sub">No other active batches to enrol in.</p>}
    <Feedback state={state} id="enroll-feedback" />
  </form>;
}

export function EnrollmentAction({ studentId, enrollment, today }: {
  studentId: string; today: string;
  enrollment: { id: string; version: number; enrolledOn: string; withdrawnOn: string | null; batchCode: string };
}) {
  const [state, action, pending] = useActionState(updateEnrollment, null);
  const withdrawn = enrollment.withdrawnOn !== null;
  return <form action={action} className="inline-form">
    <input type="hidden" name="id" value={enrollment.id} />
    <input type="hidden" name="studentId" value={studentId} />
    <input type="hidden" name="version" value={enrollment.version} />
    <input type="hidden" name="enrolledOn" value={enrollment.enrolledOn} />
    {!withdrawn && <>
      <label className="sr-only" htmlFor={`withdraw-${enrollment.id}`}>Withdrawal date for {enrollment.batchCode}</label>
      <input id={`withdraw-${enrollment.id}`} name="withdrawnOn" type="date" required defaultValue={today} min={enrollment.enrolledOn}
        className="inline-date" />
    </>}
    <button className="link-button" type="submit" disabled={pending}
      aria-label={`${withdrawn ? "Re-enrol in" : "Withdraw from"} ${enrollment.batchCode}`}>
      {pending ? "…" : withdrawn ? "Re-enrol" : "Withdraw"}
    </button>
    {state && !state.ok && <span className="inline-error" role="alert">{state.message}</span>}
  </form>;
}

/** Erase a student's personal data on request. The record stays, anonymised, for attendance figures. */
export function EraseStudentForm({ studentId }: { studentId: string }) {
  const [state, action, pending] = useActionState(eraseStudent, null);
  const values = state && !state.ok ? state.values : undefined;
  return <details className="decision erase"><summary>Erase this student’s personal data</summary>
    <form key={JSON.stringify(state)} action={action} className="session-form decision-form">
      <input type="hidden" name="studentId" value={studentId} />
      <p className="panel-sub">Removes their name, number, sign-in, Zoom names and emails, reactions and appeal texts. Attendance figures stay,
        anonymised. This cannot be undone.</p>
      <label htmlFor="erase-confirm">Type the student number to confirm</label>
      <input id="erase-confirm" name="confirm" autoComplete="off" required maxLength={60} />
      <label htmlFor="erase-reason">Reason <span className="muted">(recorded)</span></label>
      <input id="erase-reason" name="reason" maxLength={300} required autoComplete="off" defaultValue={values?.reason} />
      <button className="action danger" type="submit" disabled={pending}>{pending ? "Erasing…" : "Erase personal data"}</button>
      {state && <p id="erase-feedback" className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
    </form></details>;
}
