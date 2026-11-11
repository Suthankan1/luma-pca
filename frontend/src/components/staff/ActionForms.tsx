"use client";

import { useActionState } from "react";
import { createBatch, createCourse, setActive } from "@/app/staff/courses/actions";
import type { FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

export function CourseForm() {
  const [state, action, pending] = useActionState(createCourse, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="course-feedback">
    <h3 className="form-title">New course</h3>
    <label htmlFor="course-code">Code</label>
    <input id="course-code" name="code" maxLength={60} required autoCapitalize="characters" placeholder="PHY-2027" defaultValue={state?.values?.code} />
    <label htmlFor="course-title">Title</label>
    <input id="course-title" name="title" maxLength={200} required placeholder="A/L Physics 2027" defaultValue={state?.values?.title} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Create course"}</button>
    <Feedback state={state} id="course-feedback" />
  </form>;
}

export function BatchForm({ courses }: { courses: { id: string; code: string; title: string }[] }) {
  const [state, action, pending] = useActionState(createBatch, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="batch-feedback">
    <h3 className="form-title">New batch</h3>
    <label htmlFor="batch-course">Course</label>
    <select id="batch-course" name="courseId" required defaultValue={state?.values?.courseId ?? ""}>
      <option value="" disabled>Choose a course</option>
      {courses.map(course => <option key={course.id} value={course.id}>{course.code} · {course.title}</option>)}
    </select>
    <label htmlFor="batch-code">Code</label>
    <input id="batch-code" name="code" maxLength={60} required autoCapitalize="characters" placeholder="MON-EVE" defaultValue={state?.values?.code} />
    <label htmlFor="batch-name">Name</label>
    <input id="batch-name" name="name" maxLength={200} required placeholder="Monday evening" defaultValue={state?.values?.name} />
    <div className="form-pair">
      <div><label htmlFor="batch-starts">Starts</label><input id="batch-starts" name="startsOn" type="date" required defaultValue={state?.values?.startsOn} /></div>
      <div><label htmlFor="batch-ends">Ends (optional)</label><input id="batch-ends" name="endsOn" type="date" defaultValue={state?.values?.endsOn} /></div>
    </div>
    <button className="action" type="submit" disabled={pending || courses.length === 0}>{pending ? "Saving…" : "Create batch"}</button>
    {courses.length === 0 && <p className="panel-sub">Create an active course first.</p>}
    <Feedback state={state} id="batch-feedback" />
  </form>;
}

export function ActiveToggle({ kind, id, version, active, label }: {
  kind: "course" | "batch"; id: string; version: number; active: boolean; label: string;
}) {
  const [state, action, pending] = useActionState(setActive, null);
  return <form action={action} className="inline-form">
    <input type="hidden" name="kind" value={kind} />
    <input type="hidden" name="id" value={id} />
    <input type="hidden" name="version" value={version} />
    <input type="hidden" name="active" value={String(!active)} />
    <button className="link-button" type="submit" disabled={pending} aria-label={`${active ? "Deactivate" : "Reactivate"} ${label}`}>
      {pending ? "…" : active ? "Deactivate" : "Reactivate"}
    </button>
    {state && !state.ok && <span className="inline-error" role="alert">{state.message}</span>}
  </form>;
}
