import Link from "next/link";
import { LiveRefresh } from "@/components/LiveRefresh";
import { Shell } from "@/components/Shell";
import { LiveOnlineChart } from "@/components/staff/LiveOnlineChart";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { studentApi } from "@/lib/staff-api";
import { addDays, academyToday, ACADEMY_TIME_ZONE, formatDuration, zonedToInstant, type MyConsent } from "@/lib/staff-forms";
import { formatClock, formatDay, renderTime } from "@/lib/insights";
import { studentTimetable, type TimetableEntry } from "@/lib/student-timetable";

type StudentLive = {
  joined: number; online: number; seen: boolean; connectedSeconds: number; firstJoinAt: string | null;
  sampledAt: string; onlineHistory: { at: string; online: number }[];
};

export const metadata = { title: "Live Now · LUMA" };

export default async function StudentLivePage() {
  const identity = await requirePortal("student");
  const now = renderTime();
  const day = academyToday(new Date(now));
  const boundary = (offset: number) => zonedToInstant(addDays(day, offset), "00:00", ACADEMY_TIME_ZONE);
  const { entries, sessions, unavailable } = await studentTimetable(boundary(-45), boundary(46));
  const candidates = sessions.filter(c => c.status === "SCHEDULED" && Date.parse(c.startsAt) <= now && Date.parse(c.endsAt) > now);
  const readings = await Promise.all(candidates.map(async session => ({
    session, response: await studentApi<StudentLive>(`/api/v1/me/class-sessions/${session.id}/live-attendance`),
  })));
  const active = readings.flatMap(({ session, response }) => response.ok && response.data ? [{ session, data: response.data }] : []);
  const failed = readings.some(({ response }) => !response.ok && response.status !== 404);
  const consent = active.length ? await studentApi<MyConsent>("/api/v1/me/engagement-consent") : null;
  const canParticipate = consent?.ok && consent.data.enabled && consent.data.granted;
  const previous = entries.filter(c => !c.cancelled && c.endsAt && Date.parse(c.endsAt) <= now)
    .sort((a, b) => Date.parse(b.endsAt!) - Date.parse(a.endsAt!))[0];
  const next = entries.find(c => !c.cancelled && Date.parse(c.startsAt) > now);

  return <Shell active="student" studentSection="live" otherPortal={hasPortal(identity, "staff") ? "staff" : undefined}>
    <header className="page-head"><div><h1 className="heading staff-heading">Live Now</h1></div></header>
    {(unavailable || failed) && <p className="session-error" role="alert">Some class details couldn’t load. The page will retry.</p>}
    <div className="student-live-stack">
      {active.length ? active.map(({ session, data }) => <section key={session.id} className="panel student-live-card student-portal-enter">
        <header>
          <div><span className="student-live-badge">Live now</span><h2>{session.title}</h2>
            <p className="panel-sub">Started {formatClock(session.startsAt, ACADEMY_TIME_ZONE)} · {session.batches.map(b => b.code).join(", ")}</p></div>
          <div className="next-class-actions">
            {canParticipate && <Link className="action secondary" href={`/student/classes/${session.id}`}>React and answer</Link>}
          </div>
        </header>
        <dl className="student-live-metrics">
          <div><dt>Students joined</dt><dd>{data.joined}</dd></div>
          <div><dt>Online now</dt><dd>{data.online}</dd></div>
          <div><dt>Your connected time</dt><dd>{data.seen ? formatDuration(data.connectedSeconds) : "No join seen yet"}</dd>
            {data.firstJoinAt && <small>First joined {formatClock(data.firstJoinAt, ACADEMY_TIME_ZONE)}</small>}</div>
        </dl>
        <LiveOnlineChart key={session.id} sessionId={session.id} history={data.onlineHistory} sampledAt={Date.parse(data.sampledAt)}
          startsAt={session.startsAt} wide />
        <p className="panel-sub">Live Zoom activity from your enrolled batches. Your final attendance appears in My Report after reconciliation.</p>
      </section>) : <>
        <section className="panel student-live-idle student-portal-enter">
          <h2>{failed || unavailable ? "Live status unavailable" : "No live class"}</h2>
          <Link className="text-link" href="/student/calendar">Open calendar →</Link>
        </section>
        <div className="student-live-neighbours">
          <Neighbour label="Previous class" entry={previous} />
          <Neighbour label="Next class" entry={next} />
        </div>
      </>}
    </div>
    <LiveRefresh label="while this page is visible" />
  </Shell>;
}

function Neighbour({ label, entry }: { label: string; entry?: TimetableEntry }) {
  return <section className="panel student-neighbour">
    <h2 className="panel-title">{label}</h2>
    {entry ? <>
      <p><strong>{entry.title}</strong></p>
      <time dateTime={entry.startsAt}>{formatDay(entry.startsAt, ACADEMY_TIME_ZONE)} · {formatClock(entry.startsAt, ACADEMY_TIME_ZONE)}</time>
      <Link className="text-link" href={`/student/calendar?month=${entry.date.slice(0, 7)}&day=${entry.date}`}>View in calendar</Link>
    </> : <p className="panel-sub">No class found within 45 days.</p>}
  </section>;
}
