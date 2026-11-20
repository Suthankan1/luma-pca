import Link from "next/link";
import { BulkInviteForm } from "@/components/staff/BulkInviteForm";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { requirePortal, studentIdLoginEnabled } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { AttendanceExportForm } from "@/components/staff/AttendanceExportForm";
import { academyToday, type BatchAttendanceSummary, type Ranking, formatDate, monthStart, rateLabel, uuidOrNull } from "@/lib/staff-forms";

type Batch = { courseId: string; id: string; code: string; name: string; startsOn: string; endsOn: string | null; active: boolean };
type Enrollment = { id: string; studentId: string; studentNumber: string; studentName: string; enrolledOn: string; withdrawnOn: string | null };

export const metadata = { title: "Batch roster · LUMA" };

/** A batch's roster, attendance summary, invitations and engagement ranking, for staff. */
export default async function RosterPage({ params, searchParams }: { params: Promise<{ batchId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const batchId = uuidOrNull((await params).batchId);
  if (!batchId) notFound();

  const query = await searchParams;
  const isoDate = (v: unknown) => typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : null;
  const sumFrom = isoDate(query.sumFrom) ?? monthStart(academyToday());
  const sumTo = isoDate(query.sumTo) ?? academyToday();

  const rankFrom = typeof query.rankFrom === "string" && /^\d{4}-\d{2}-\d{2}$/.test(query.rankFrom) ? query.rankFrom : null;
  const rankTo = typeof query.rankTo === "string" && /^\d{4}-\d{2}-\d{2}$/.test(query.rankTo) ? query.rankTo : null;
  const top = query.top === "100" ? 100 : 10;
  const wholeCourse = query.scope === "course";
  const rankingQuery = rankFrom && rankTo ? new URLSearchParams({ from: rankFrom, to: rankTo, limit: String(top) }) : null;

  // Single round of parallel requests instead of 4 sequential rounds
  const [batch, roster, summary] = await Promise.all([
    staffApi<Batch>(`/api/v1/batches/${batchId}`),
    staffApi<Enrollment[]>(`/api/v1/batches/${batchId}/enrollments`),
    staffApi<BatchAttendanceSummary>(`/api/v1/batches/${batchId}/attendance/summary?${new URLSearchParams({ from: sumFrom, to: sumTo })}`),
  ]);

  // The API answers 404 for a batch outside the caller's academy.
  if (!batch.ok && (batch.status === 404 || batch.status === 403)) notFound();

  const rankingBase: `/api/v1/${string}` = wholeCourse && batch.ok ? `/api/v1/courses/${batch.data.courseId}/engagement-ranking`
    : `/api/v1/batches/${batchId}/engagement-ranking`;
  const ranking = rankingQuery ? await staffApi<Ranking>(`${rankingBase}?${rankingQuery}`) : null;
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
    <p className="link-row"><Link className="text-link" href="/staff/courses">← Courses &amp; batches</Link></p>
    <section className="panel" aria-labelledby="roster-title">
      <h2 id="roster-title" className="panel-title">ROSTER</h2>
      <p className="panel-sub">{current.length} enrolled{roster.data.length > current.length ? ` · ${roster.data.length - current.length} withdrawn` : ""}</p>
      <ul className="dir-list">
        {roster.data.map(e => <li key={e.id} className="dir-row dir-course">
          <div><Link className="text-link" href={`/staff/students/${e.studentId}`}><strong>{e.studentNumber}</strong> <span>{e.studentName}</span></Link></div>
          <span className={`status ${e.withdrawnOn ? "off" : "on"}`}>{e.withdrawnOn ? `Withdrawn ${formatDate(e.withdrawnOn)}` : `Since ${formatDate(e.enrolledOn)}`}</span>
        </li>)}
        {roster.data.length === 0 && <li className="dir-empty muted">No students enrolled yet.</li>}
      </ul>
    </section>
    <section className="panel panel-gap" aria-labelledby="batch-export-title">
      <h2 id="batch-export-title" className="panel-title">ATTENDANCE EXPORT</h2>
      <p className="panel-sub">A CSV of this batch’s students in its classes between two dates (at most 92 days), with the same facts as each
        class page: time connected, first join, visits and whether the figures come from Zoom’s report. It contains personal data; keep it private.
        Every export is recorded in the activity log.</p>
      <AttendanceExportForm batchId={batchId} from={daysBefore(academyToday(), 30)} to={academyToday()} />
    </section>
    <section className="panel panel-gap" aria-labelledby="batch-invite-title">
      <h2 id="batch-invite-title" className="panel-title">INVITE BY EMAIL</h2>
      <Disclosure summary="Email sign-in links to this batch’s students">
        {studentIdLoginEnabled() && <p className="panel-sub">Students can sign in with their student ID, so invitations are only needed
          for email sign-in. Students without an email of their own are never emailed.</p>}
        <BulkInviteForm batchId={batchId} scope="this batch’s current students" />
      </Disclosure>
    </section>
    <section className="panel panel-gap" aria-labelledby="summary-title">
      <h2 id="summary-title" className="panel-title">ATTENDANCE SUMMARY</h2>
      <p className="panel-sub">Each student’s final attendance in this batch’s classes, lowest rate first. Rate = (present + late) ÷
        (present + late + absent); excused classes are left out.</p>
      <form method="get" className="export-range" aria-label="Summary range">
        <label htmlFor="sum-from">From</label>
        <input id="sum-from" name="sumFrom" type="date" required defaultValue={sumFrom} />
        <label htmlFor="sum-to">To</label>
        <input id="sum-to" name="sumTo" type="date" required defaultValue={sumTo} />
        <button className="action secondary" type="submit">Show summary</button>
      </form>
      {!summary.ok ? <p className="session-error" role="alert">{summary.detail ?? "We couldn’t load the summary right now."}</p> : <>
        <p className="panel-sub">{summary.data.finalClasses} final class{summary.data.finalClasses === 1 ? "" : "es"}
          {summary.data.classesNotFinal > 0 && ` · ${summary.data.classesNotFinal} ended but not final yet`} · batch rate {rateLabel(summary.data.totals)}</p>
        <ul className="dir-list">{summary.data.students.map(r => <li key={r.studentId} className="dir-row" aria-label={`Summary ${r.studentNumber}`}>
          <span><strong>{r.studentNumber}</strong> {r.fullName}</span>
          <span><strong className="summary-rate">{rateLabel(r.counts)}</strong> <span className="muted">{r.counts.present} present · {r.counts.late} late ·
            {" "}{r.counts.absent} absent{r.counts.excused ? ` · ${r.counts.excused} excused` : ""}</span></span>
        </li>)}
          {summary.data.students.length === 0 && <li className="dir-empty muted">No final attendance in this range yet.</li>}</ul>
      </>}
    </section>
    <section className="panel panel-gap" aria-labelledby="ranking-title">
      <h2 id="ranking-title" className="panel-title">ENGAGEMENT RANKING</h2>
      <Disclosure summary="Show the staff-only engagement ranking" defaultOpen={!!rankingQuery}>
      <p className="panel-sub">Staff only. Students who consented to engagement, scored by portal reactions (at most 10 per class) and
        three points per question answered, divided by the classes they were expected at. Students are named only if they opted in.</p>
      <form method="get" className="export-range" aria-label="Ranking range">
        <label htmlFor="rank-from">From</label>
        <input id="rank-from" name="rankFrom" type="date" required defaultValue={rankFrom ?? daysBefore(academyToday(), 30)} />
        <label htmlFor="rank-to">To</label>
        <input id="rank-to" name="rankTo" type="date" required defaultValue={rankTo ?? academyToday()} />
        <label htmlFor="rank-top">Show</label>
        <select id="rank-top" name="top" defaultValue={String(top)}><option value="10">Top 10</option><option value="100">Top 100</option></select>
        <label htmlFor="rank-scope">Over</label>
        <select id="rank-scope" name="scope" defaultValue={wholeCourse ? "course" : "batch"}><option value="batch">This batch</option><option value="course">The whole course</option></select>
        <button className="action secondary" type="submit">Show ranking</button>
      </form>
      {ranking && (!ranking.ok ? <p className="session-error" role="alert">{ranking.detail ?? "We couldn’t load the ranking right now."}</p>
        : <>
          <p className="panel-sub">{ranking.data.classes} class{ranking.data.classes === 1 ? "" : "es"} · {ranking.data.studentsCounted} consenting student{ranking.data.studentsCounted === 1 ? "" : "s"}</p>
          {rankingQuery && <p className="link-row"><a className="text-link" download
            href={`/staff/batches/${batchId}/ranking-export?${new URLSearchParams({ ...Object.fromEntries(rankingQuery), scope: wholeCourse ? "course" : "batch" })}`}>Download CSV</a></p>}
          <ol className="dir-list ranking">{ranking.data.top.map(r => <li key={r.studentId} className="dir-row" aria-label={`Rank ${r.rank}`}>
            <span><strong>{r.rank}.</strong> {r.studentNumber ? `${r.studentNumber} ${r.name ?? ""}` : <span className="muted">Not named (no opt-in)</span>}</span>
            <span className="muted">{r.score} · {r.reactions} reactions, {r.pulseAnswers} answers, {r.classes} classes</span>
          </li>)}
            {ranking.data.top.length === 0 && <li className="dir-empty muted">No consenting students took part in this range.</li>}</ol>
        </>)}
      </Disclosure>
    </section>
  </StaffShell>;
}

/** The ISO date `days` before an ISO date. */
function daysBefore(isoDate: string, days: number) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}
