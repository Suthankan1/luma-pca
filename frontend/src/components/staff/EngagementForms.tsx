"use client";

import { useActionState } from "react";
import { closePulse, openPulse, saveEngagementSettings } from "@/app/staff/engagement/actions";
import type { EngagementSettings, FormState } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

export function EngagementSettingsForm({ settings }: { settings: EngagementSettings | null }) {
  const [state, action, pending] = useActionState(saveEngagementSettings, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form">
    {settings && <input type="hidden" name="version" value={settings.version} />}
    <label className="check"><input type="checkbox" name="enabled" defaultChecked={settings?.enabled ?? false} /> Engagement is on</label>
    <label htmlFor="consent-text">What students agree to</label>
    <textarea id="consent-text" name="consentText" rows={4} maxLength={2000} required
      defaultValue={values?.consentText ?? settings?.consentText ?? "LUMA records the reactions and answers you send from this portal during classes, so staff can see how the class is going. You can turn this off at any time."} />
    <button className="action" type="submit" disabled={pending}>{pending ? "Saving…" : "Save engagement settings"}</button>
    <Feedback state={state} id="engagement-feedback" />
  </form>;
}

export function PulseForm({ sessionId }: { sessionId: string }) {
  const [state, action, pending] = useActionState(openPulse, null);
  const values = state && !state.ok ? state.values : undefined;
  return <form key={JSON.stringify(state)} action={action} className="session-form">
    <input type="hidden" name="sessionId" value={sessionId} />
    <label htmlFor="pulse-prompt">Question</label>
    <input id="pulse-prompt" name="prompt" maxLength={300} required autoComplete="off" defaultValue={values?.prompt} />
    <label htmlFor="pulse-options">Options, one per line (2 to 5)</label>
    <textarea id="pulse-options" name="options" rows={3} required defaultValue={values?.options} />
    <label htmlFor="pulse-minutes">Open for (minutes)</label>
    <input id="pulse-minutes" name="minutes" type="number" min={1} max={10} required defaultValue={values?.minutes ?? "3"} />
    <button className="action secondary" type="submit" disabled={pending}>{pending ? "Opening…" : "Open question"}</button>
    <Feedback state={state} id="pulse-feedback" />
  </form>;
}

export function ClosePulseButton({ sessionId, questionId }: { sessionId: string; questionId: string }) {
  const [state, action, pending] = useActionState(closePulse, null);
  return <form action={action} className="inline-form">
    <input type="hidden" name="sessionId" value={sessionId} />
    <input type="hidden" name="questionId" value={questionId} />
    <button className="action secondary" type="submit" disabled={pending}>Close now</button>
    <Feedback state={state} id={`close-${questionId}`} />
  </form>;
}
