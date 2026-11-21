"use client";

import { useActionState } from "react";
import { addWebinar, webinarAction } from "@/app/staff/sessions/webinar-actions";

function Feedback({ state, id }: { state: { ok: boolean; message: string } | null; id: string }) {
  return <p id={id} className={state ? (state.ok ? "form-ok" : "session-error") : "sr-only"} role={state?.ok === false ? "alert" : "status"}>
    {state?.message ?? ""}</p>;
}

/** The only input LUMA needs: a webinar ID or its join link. */
export function AddWebinarForm() {
  const [state, action, pending] = useActionState(addWebinar, null);
  return <form key={state?.ok ? "done" : "editing"} action={action} className="add-webinar" aria-describedby="add-webinar-feedback">
    <label htmlFor="add-webinar">Zoom link or webinar ID to track</label>
    <div className="add-webinar-row">
      <input id="add-webinar" name="webinar" required maxLength={500} inputMode="url" autoComplete="off" spellCheck={false}
        placeholder="844 0000 0101 or https://zoom.us/j/84400000101" defaultValue={state?.values?.webinar} />
      <button className="action" type="submit" disabled={pending}>{pending ? "Checking with Zoom…" : "Track"}</button>
    </div>
    <p className="panel-sub">Title, time and recurrence are read from Zoom. LUMA never changes the webinar.</p>
    <Feedback state={state} id="add-webinar-feedback" />
  </form>;
}

/** Refresh from Zoom, stop/resume and the Academy class: one action state per webinar, so its outcome stays visible. */
export function WebinarActions({ monitorId, topic, active, academyClassName, classOptions }: {
  monitorId: string; topic: string; active: boolean; academyClassName: string | null; classOptions: string[] | null;
}) {
  const [state, action, pending] = useActionState(webinarAction, null);
  const feedback = `webinar-feedback-${monitorId}`;
  return <div className="webinar-actions">
    <form action={action} className="inline-form">
      <input type="hidden" name="monitorId" value={monitorId} />
      <input type="hidden" name="intent" value="sync" />
      <button className="action secondary" type="submit" disabled={pending} aria-label={`Read ${topic} again from Zoom`}
        aria-describedby={feedback}>Refresh from Zoom</button>
    </form>
    <form action={action} className="inline-form">
      <input type="hidden" name="monitorId" value={monitorId} />
      <input type="hidden" name="intent" value={active ? "stop" : "resume"} />
      <button className="action secondary" type="submit" disabled={pending} aria-label={`${active ? "Stop tracking" : "Track again"} ${topic}`}
        aria-describedby={feedback}>{active ? "Stop tracking" : "Track again"}</button>
    </form>
    {classOptions && <form action={action} className="class-choice">
      <input type="hidden" name="monitorId" value={monitorId} />
      <input type="hidden" name="intent" value="class" />
      <label htmlFor={`class-${monitorId}`}>Academy class</label>
      <div className="inline-form">
        <select id={`class-${monitorId}`} name="academyClassName" defaultValue={academyClassName ?? ""}>
          <option value="">Unresolved (no students expected)</option>
          {classOptions.map(name => <option key={name} value={name}>{name}</option>)}
        </select>
        <button className="action secondary" type="submit" disabled={pending}>Save</button>
      </div>
    </form>}
    <Feedback state={state} id={feedback} />
  </div>;
}
