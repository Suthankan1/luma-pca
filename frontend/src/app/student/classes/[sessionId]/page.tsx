import Link from "next/link";
import { notFound } from "next/navigation";
import { Shell } from "@/components/Shell";
import { PulseAnswer, ReactionButtons } from "@/components/StudentEngagement";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { uuidOrNull, type ClassEngagement } from "@/lib/staff-forms";

export const metadata = { title: "Class · LUMA" };

/** During a class: reactions and the questions staff have opened. Only for consenting students; the API checks everything. */
export default async function StudentClassPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const identity = await requirePortal("student");
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) notFound();
  const engagement = await studentApi<ClassEngagement>(`/api/v1/me/class-sessions/${sessionId}/engagement`);
  if (!engagement.ok && engagement.status === 404) notFound();
  return <Shell active="student" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <p><Link className="text-link" href="/student">← My classes</Link></p>
    <h1 className="heading">Take part</h1>
    {!engagement.ok ? <p className="session-error" role="alert">We couldn’t load this class right now.</p>
      : !engagement.data.enabled ? <p className="banner">Reactions and questions are not switched on at the academy.</p>
      : <>
        <section className="panel" aria-labelledby="react-title">
          <h2 id="react-title" className="panel-title">HOW IS IT GOING?</h2>
          <p className="panel-sub">Your reaction goes to staff with your name. One every few seconds.</p>
          <ReactionButtons sessionId={sessionId} />
        </section>
        <section className="panel" aria-labelledby="questions-title">
          <h2 id="questions-title" className="panel-title">QUESTIONS</h2>
          {engagement.data.pulses.length === 0 ? <p className="muted">No question is open right now. Reload when your teacher asks one.</p>
            : engagement.data.pulses.map(p => <PulseAnswer key={p.id} sessionId={sessionId} pulse={p} />)}
        </section>
      </>}
  </Shell>;
}
