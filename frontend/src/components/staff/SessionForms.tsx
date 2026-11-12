"use client";

import Link from "next/link";
import { useActionState } from "react";
import { cancelSeries, editSeries, scheduleSeries, scheduleSession, updateSession } from "@/app/staff/sessions/actions";
import { WEEKDAYS, type FormState } from "@/lib/staff-forms";

export type BatchOption = { id: string; label: string };
export type SessionDefaults = { title: string; date: string; startTime: string; endTime: string; zoomWebinarId: string; batchIds: string[] };

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

/** The fields shared by scheduling and editing; `prefix` keeps element ids unique on a page. */
function SessionFields({ prefix, batches, defaults, zoneLabel }: { prefix: string; batches: BatchOption[]; defaults?: Partial<SessionDefaults>; zoneLabel: string }) {
  return <>
    <label htmlFor={`${prefix}-title`}>Title</label>
    <input id={`${prefix}-title`} name="title" maxLength={200} required defaultValue={defaults?.title} placeholder="Waves and oscillations" />
    <label htmlFor={`${prefix}-date`}>Date</label>
    <input id={`${prefix}-date`} name="date" type="date" required defaultValue={defaults?.date} />
    <div className="form-pair">
      <div><label htmlFor={`${prefix}-start`}>Starts</label>
        <input id={`${prefix}-start`} name="startTime" type="time" required defaultValue={defaults?.startTime} /></div>
      <div><label htmlFor={`${prefix}-end`}>Ends</label>
        <input id={`${prefix}-end`} name="endTime" type="time" required defaultValue={defaults?.endTime} /></div>
    </div>
    <p className="panel-sub">Times are {zoneLabel}. A class ends on the day it starts and lasts at most 12 hours.</p>
    <fieldset className="role-choices">
      <legend>Batches taking this class</legend>
      {batches.length === 0 && <p className="muted">There are no active batches. Create one under Courses &amp; batches first.</p>}
      {batches.map(batch => <label key={batch.id} className="check-row">
        <input type="checkbox" name="batchIds" value={batch.id} defaultChecked={defaults?.batchIds?.includes(batch.id)} /> {batch.label}
      </label>)}
    </fieldset>
    <label htmlFor={`${prefix}-webinar`}>Zoom webinar ID <span className="muted">(optional)</span></label>
    <input id={`${prefix}-webinar`} name="zoomWebinarId" inputMode="numeric" maxLength={40} autoComplete="off"
      defaultValue={defaults?.zoomWebinarId} placeholder="812 3456 7890" />
    <p className="panel-sub">With a webinar ID, Zoom events during the class are linked to it automatically.</p>
  </>;
}

/** After a refusal the submitted values come back; checkbox choices travel comma-joined. */
function refused(state: FormState): Partial<SessionDefaults> | undefined {
  if (!state || state.ok || !state.values) return undefined;
  const { batchIds, ...rest } = state.values;
  return { ...rest, batchIds: batchIds ? batchIds.split(",") : [] };
}

export function ScheduleForm({ batches }: { batches: BatchOption[] }) {
  const [state, action, pending] = useActionState(scheduleSession, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="schedule-feedback">
    <h3 className="form-title">Schedule a class</h3>
    <SessionFields prefix="schedule" batches={batches} defaults={refused(state)} zoneLabel="Sri Lanka time (Asia/Colombo)" />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Schedule class"}</button>
    <Feedback state={state} id="schedule-feedback" />
    {state?.ok && state.values?.link && <p><Link className="text-link" href={state.values.link}>Open the class →</Link></p>}
  </form>;
}

type Existing = { id: string; version: number; status: string; timeZone: string };

export function EditSessionForm({ session, batches, defaults }: { session: Existing; batches: BatchOption[]; defaults: SessionDefaults }) {
  const [state, action, pending] = useActionState(updateSession, null);
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="edit-feedback">
    <h3 className="form-title">Edit class</h3>
    <input type="hidden" name="id" value={session.id} />
    <input type="hidden" name="version" value={session.version} />
    <input type="hidden" name="status" value={session.status} />
    <input type="hidden" name="timeZone" value={session.timeZone} />
    <SessionFields prefix="edit" batches={batches} defaults={refused(state) ?? defaults} zoneLabel={`in ${session.timeZone}`} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save changes"}</button>
    <Feedback state={state} id="edit-feedback" />
  </form>;
}

/** Cancelling keeps the record (and anything Zoom later links to it); restoring makes it scheduled again. */
export function StatusForm({ session, defaults }: { session: Existing; defaults: SessionDefaults }) {
  const [state, action, pending] = useActionState(updateSession, null);
  const cancelling = session.status === "SCHEDULED";
  return <form action={action} className={`session-form staff-form ${cancelling ? "danger-zone" : ""}`} aria-describedby="status-feedback">
    <h3 className="form-title">{cancelling ? "Cancel class" : "Restore class"}</h3>
    <input type="hidden" name="intent" value="status" />
    <input type="hidden" name="id" value={session.id} />
    <input type="hidden" name="version" value={session.version} />
    <input type="hidden" name="status" value={cancelling ? "CANCELLED" : "SCHEDULED"} />
    <input type="hidden" name="timeZone" value={session.timeZone} />
    {(["title", "date", "startTime", "endTime", "zoomWebinarId"] as const).map(name =>
      <input key={name} type="hidden" name={name} value={defaults[name]} />)}
    {defaults.batchIds.map(id => <input key={id} type="hidden" name="batchIds" value={id} />)}
    <p className="panel-sub">{cancelling ? "The class stays on record as cancelled and Zoom events are no longer linked to it automatically. You can restore it."
      : "Makes the class scheduled again."}</p>
    <button className={`action ${cancelling ? "danger" : ""}`} type="submit" disabled={pending}>
      {pending ? "Saving…" : cancelling ? "Cancel class" : "Restore class"}</button>
    <Feedback state={state} id="status-feedback" />
  </form>;
}

const dayNames: Record<string, string> = { MONDAY: "Mon", TUESDAY: "Tue", WEDNESDAY: "Wed", THURSDAY: "Thu", FRIDAY: "Fri", SATURDAY: "Sat", SUNDAY: "Sun" };

/**
 * A weekly series: "Preview classes" lists every class without creating anything; "Create"
 * appears after a clean preview and is refused if the details changed since.
 */
export function SeriesForm({ batches }: { batches: BatchOption[] }) {
  const [state, action, pending] = useActionState(scheduleSeries, null);
  const created = state?.ok && !state.preview && state.values?.link;
  const v = created ? undefined : state?.values;
  const chosenBatches = v?.batchIds ? v.batchIds.split(",") : [];
  const chosenDays = v?.weekdays ? v.weekdays.split(",") : [];
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="series-feedback">
    <h3 className="form-title">Schedule a weekly series</h3>
    <label htmlFor="series-title">Title</label>
    <input id="series-title" name="title" maxLength={200} required defaultValue={v?.title} placeholder="Physics theory" />
    <div className="form-pair">
      <div><label htmlFor="series-first">First date</label>
        <input id="series-first" name="firstDate" type="date" required defaultValue={v?.firstDate} /></div>
      <div><label htmlFor="series-last">Last date</label>
        <input id="series-last" name="lastDate" type="date" required defaultValue={v?.lastDate} /></div>
    </div>
    <fieldset className="role-choices weekday-choices">
      <legend>Every</legend>
      {WEEKDAYS.map(day => <label key={day} className="check-row">
        <input type="checkbox" name="weekdays" value={day} defaultChecked={chosenDays.includes(day)} /> {dayNames[day]}
      </label>)}
    </fieldset>
    <div className="form-pair">
      <div><label htmlFor="series-start">Starts</label>
        <input id="series-start" name="startTime" type="time" required defaultValue={v?.startTime} /></div>
      <div><label htmlFor="series-end">Ends</label>
        <input id="series-end" name="endTime" type="time" required defaultValue={v?.endTime} /></div>
    </div>
    <p className="panel-sub">Sri Lanka time (Asia/Colombo). Up to 100 classes within a year.</p>
    <label htmlFor="series-skip">Dates to skip <span className="muted">(optional, such as holidays)</span></label>
    <textarea id="series-skip" name="skipDates" rows={2} maxLength={2000} defaultValue={v?.skipDates} placeholder="2027-01-14, 2027-02-04" />
    <fieldset className="role-choices">
      <legend>Batches taking these classes</legend>
      {batches.length === 0 && <p className="muted">There are no active batches. Create one under Courses &amp; batches first.</p>}
      {batches.map(batch => <label key={batch.id} className="check-row">
        <input type="checkbox" name="batchIds" value={batch.id} defaultChecked={chosenBatches.includes(batch.id)} /> {batch.label}
      </label>)}
    </fieldset>
    <label htmlFor="series-webinar">Zoom webinar ID <span className="muted">(optional; a recurring webinar keeps one ID)</span></label>
    <input id="series-webinar" name="zoomWebinarId" inputMode="numeric" maxLength={40} autoComplete="off" defaultValue={v?.zoomWebinarId}
      placeholder="812 3456 7890" />
    {state?.preview && <input type="hidden" name="previewKey" value={state.preview.key} />}
    <div className="button-row">
      <button className={`action ${state?.preview ? "secondary" : ""}`} type="submit" name="intent" value="preview" disabled={pending}>
        {pending ? "Working…" : "Preview classes"}</button>
      {state?.preview && <button className="action" type="submit" name="intent" value="create" disabled={pending}>
        Create {state.preview.classes.length} class{state.preview.classes.length === 1 ? "" : "es"}</button>}
    </div>
    {state && <p id="series-feedback" className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
    {state?.preview && <ol className="series-preview" aria-label="Classes in the series">
      {state.preview.classes.map(label => <li key={label}>{label}</li>)}
    </ol>}
    {state?.conflicts && <ul className="series-preview" aria-label="Overlapping classes">
      {state.conflicts.map(c => <li key={c.label}><Link className="text-link" href={c.link}>{c.label}</Link></li>)}
    </ul>}
    {created && <p><Link className="text-link" href={state.values!.link}>Show the classes →</Link></p>}
  </form>;
}

/** Cancels this and later scheduled classes of the class's series (each stays on record as cancelled). */
export function CancelSeriesForm({ sessionId, seriesId, fromDate }: { sessionId: string; seriesId: string; fromDate: string }) {
  const [state, action, pending] = useActionState(cancelSeries, null);
  return <form action={action} className="session-form staff-form danger-zone" aria-describedby="series-cancel-feedback">
    <h3 className="form-title">Cancel the rest of the series</h3>
    <input type="hidden" name="seriesId" value={seriesId} />
    <input type="hidden" name="sessionId" value={sessionId} />
    <label htmlFor="series-cancel-from">Cancel scheduled classes from</label>
    <input id="series-cancel-from" name="fromDate" type="date" required defaultValue={fromDate} />
    <p className="panel-sub">Every scheduled class of this weekly series on or after this date is cancelled. Each can be restored on its own page.</p>
    <button className="action danger" type="submit" disabled={pending}>{pending ? "Saving…" : "Cancel these classes"}</button>
    <Feedback state={state} id="series-cancel-feedback" />
  </form>;
}

/** Changes the title, time or webinar of this and later scheduled classes of the class's series, after a preview. */
export function EditSeriesForm({ sessionId, seriesId, fromDate, timeZone }: { sessionId: string; seriesId: string; fromDate: string; timeZone: string }) {
  const [state, action, pending] = useActionState(editSeries, null);
  const v = state?.ok && !state.preview ? undefined : state?.values;
  return <form key={JSON.stringify(state)} action={action} className="session-form staff-form" aria-describedby="series-edit-feedback">
    <h3 className="form-title">Change the rest of the series</h3>
    <input type="hidden" name="seriesId" value={seriesId} />
    <input type="hidden" name="sessionId" value={sessionId} />
    <input type="hidden" name="timeZone" value={timeZone} />
    <label htmlFor="series-edit-from">Change scheduled classes from</label>
    <input id="series-edit-from" name="fromDate" type="date" required defaultValue={v?.fromDate ?? fromDate} />
    <p className="panel-sub">Leave a field empty to keep it. Times are in {timeZone}; each class keeps its own date and batches.</p>
    <label htmlFor="series-edit-title">New title</label>
    <input id="series-edit-title" name="title" maxLength={200} defaultValue={v?.title} />
    <div className="form-pair">
      <div><label htmlFor="series-edit-start">New start</label>
        <input id="series-edit-start" name="startTime" type="time" defaultValue={v?.startTime} /></div>
      <div><label htmlFor="series-edit-end">New end</label>
        <input id="series-edit-end" name="endTime" type="time" defaultValue={v?.endTime} /></div>
    </div>
    <label htmlFor="series-edit-webinar">New Zoom webinar ID</label>
    <input id="series-edit-webinar" name="zoomWebinarId" inputMode="numeric" maxLength={40} autoComplete="off" defaultValue={v?.zoomWebinarId} />
    <label className="check-row"><input type="checkbox" name="clearWebinar" value="yes" defaultChecked={v?.clearWebinar === "yes"} /> Remove the webinar ID</label>
    {state?.preview && <input type="hidden" name="previewKey" value={state.preview.key} />}
    <div className="button-row">
      <button className={`action ${state?.preview ? "secondary" : ""}`} type="submit" name="intent" value="preview" disabled={pending}>
        {pending ? "Working…" : "Preview changes"}</button>
      {state?.preview && <button className="action" type="submit" name="intent" value="apply" disabled={pending}>
        Change {state.preview.classes.length} class{state.preview.classes.length === 1 ? "" : "es"}</button>}
    </div>
    {state && <p id="series-edit-feedback" className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>}
    {state?.preview && <ol className="series-preview" aria-label="Classes after the change">
      {state.preview.classes.map(label => <li key={label}>{label}</li>)}
    </ol>}
    {state?.conflicts && <ul className="series-preview" aria-label="Overlapping classes">
      {state.conflicts.map(c => <li key={c.label}><Link className="text-link" href={c.link}>{c.label}</Link></li>)}
    </ul>}
  </form>;
}
