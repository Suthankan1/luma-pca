"use client";

import { useActionState } from "react";
import { answerPulse, saveConsent, sendReaction } from "@/app/student/actions";
import { reactionLabels, type FormState, type MyConsent, type PulseView } from "@/lib/staff-forms";

function Feedback({ state, id }: { state: FormState; id: string }) {
  if (!state) return null;
  return <p id={id} className={state.ok ? "form-ok" : "session-error"} role={state.ok ? "status" : "alert"}>{state.message}</p>;
}

export function ConsentForm({ consent }: { consent: MyConsent }) {
  const [state, action, pending] = useActionState(saveConsent, null);
  return <form action={action} className="session-form">
    <input type="hidden" name="consentVersion" value={consent.consentVersion} />
    <blockquote className="consent-text">{consent.consentText}</blockquote>
    <label className="check"><input type="checkbox" name="leaderboard" defaultChecked={consent.leaderboardOptIn} /> Staff may also see my name in engagement rankings</label>
    <div className="button-row">
      <button className="action" type="submit" name="granted" value="yes" disabled={pending}>I agree</button>
      <button className="action secondary" type="submit" name="granted" value="no" disabled={pending}>No thanks</button>
    </div>
    <Feedback state={state} id="consent-feedback" />
  </form>;
}

export function ReactionButtons({ sessionId }: { sessionId: string }) {
  const [state, action, pending] = useActionState(sendReaction, null);
  return <form action={action} className="reaction-row">
    <input type="hidden" name="sessionId" value={sessionId} />
    {Object.entries(reactionLabels).map(([kind, label]) =>
      <button key={kind} className="action secondary" type="submit" name="kind" value={kind} disabled={pending}>{label}</button>)}
    <Feedback state={state} id="reaction-feedback" />
  </form>;
}

export function PulseAnswer({ sessionId, pulse }: { sessionId: string; pulse: PulseView }) {
  const [state, action, pending] = useActionState(answerPulse, null);
  if (pulse.myAnswer != null) return <p className="muted">You answered “{pulse.options[pulse.myAnswer]}”.</p>;
  return <form action={action} className="session-form">
    <input type="hidden" name="sessionId" value={sessionId} />
    <input type="hidden" name="questionId" value={pulse.id} />
    <fieldset><legend>{pulse.prompt}</legend>
      {pulse.options.map((o, i) => <label key={i} className="check"><input type="radio" name="option" value={i} required /> {o}</label>)}
    </fieldset>
    <button className="action" type="submit" disabled={pending}>Send answer</button>
    <Feedback state={state} id={`pulse-${pulse.id}-feedback`} />
  </form>;
}
