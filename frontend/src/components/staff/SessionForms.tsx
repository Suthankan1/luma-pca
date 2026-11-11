"use client";

import Link from "next/link";
import { useActionState } from "react";
import { scheduleSession, updateSession } from "@/app/staff/sessions/actions";
import type { FormState } from "@/lib/staff-forms";

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
