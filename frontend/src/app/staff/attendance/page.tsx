import { StaffShell } from "@/components/StaffShell";
import { AppealDecisionForm, PolicyForm } from "@/components/staff/AttendanceForms";
import { EngagementSettingsForm } from "@/components/staff/EngagementForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import Link from "next/link";
import { academyToday, formatInstant, isAcademyManager, monthStart, outcomeLabels, rateLabel, type AcademyAttendanceSummary, type AttendanceAppeal, type AttendancePolicy, type EngagementSettings } from "@/lib/staff-forms";

export const metadata = { title: "Attendance · LUMA" };

/** The academy's attendance rule. Everyone who sees attendance can read it; only an owner records it. */
export default async function AttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const owner = identity.roles.includes("ACADEMY_OWNER");
  const shell = { active: "attendance" as const, crumb: "Oversight", title: "Attendance",
    subtitle: "The academy’s rule for final attendance, applied only to Zoom’s official participant report.",
    hasStudentPortal: hasPortal(identity, "student") };
  const manager = isAcademyManager(identity.roles);
  const reports = manager || identity.roles.includes("ANALYST");
  const query = await searchParams;
  const isoDate = (v: unknown) => typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : null;
  const reportFrom = isoDate(query.from) ?? monthStart(academyToday());
  const reportTo = isoDate(query.to) ?? academyToday();
  const [policy, appeals, engagement, report] = await Promise.all([
    staffApi<AttendancePolicy | null>("/api/v1/attendance-policy"),
    manager ? staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN") : Promise.resolve(null),
    manager ? staffApi<EngagementSettings | null>("/api/v1/engagement-settings") : Promise.resolve(null),
    reports ? staffApi<AcademyAttendanceSummary>(`/api/v1/attendance/summary?${new URLSearchParams({ from: reportFrom, to: reportTo })}`)
      : Promise.resolve(null),
  ]);
  if (!policy.ok) {
    return <StaffShell {...shell}><p className="session-error" role="alert">
      {policy.status === 403 ? "Your role does not include attendance." : "We couldn’t load the attendance rule right now. Please reload shortly."}</p></StaffShell>;
  }
  const rule = policy.data;
  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="rule-title">
      <h2 id="rule-title" className="panel-title">ATTENDANCE RULE</h2>
      {rule ? <>
        <p className="rule-summary"><strong>Present</strong> when connected for at least {rule.minConnectedPercent}% of the class time;
          {" "}<strong>late</strong> when also first joining more than {rule.lateAfterMinutes} minute{rule.lateAfterMinutes === 1 ? "" : "s"} after
          {" "}the start; otherwise <strong>absent</strong>.</p>
        <p className="panel-sub">{rule.description} · last changed {formatInstant(rule.updatedAt)}. Classes finalised earlier keep the rule they used.</p>
      </> : <p className="banner">No rule has been recorded yet, so no class can be finalised. The rule is the academy’s decision; LUMA only applies it.</p>}
      {owner ? <PolicyForm policy={rule} /> : <p className="panel-sub">Only an academy owner can record or change the rule.</p>}
    </section>
    {report && <section className="panel panel-gap" aria-labelledby="report-title">
      <h2 id="report-title" className="panel-title">ATTENDANCE BY BATCH</h2>
      <p className="panel-sub">Final attendance totals per batch, without student names. Rate = (present + late) ÷ (present + late + absent);
        excused classes are left out.</p>
      <form method="get" className="export-range" aria-label="Report range">
        <label htmlFor="report-from">From</label>
        <input id="report-from" name="from" type="date" required defaultValue={reportFrom} />
        <label htmlFor="report-to">To</label>
        <input id="report-to" name="to" type="date" required defaultValue={reportTo} />
        <button className="action secondary" type="submit">Show report</button>
      </form>
      {!report.ok ? <p className="session-error" role="alert">{report.detail ?? "We couldn’t load the report right now."}</p>
        : <ul className="dir-list">{report.data.batches.map(b => <li key={b.batchId} className="dir-row" aria-label={`Batch ${b.courseCode} ${b.batchCode}`}>
          <span>{manager ? <Link className="text-link" href={`/staff/batches/${b.batchId}?sumFrom=${reportFrom}&sumTo=${reportTo}`}>
            <strong>{b.courseCode} · {b.batchCode}</strong> {b.batchName}</Link> : <><strong>{b.courseCode} · {b.batchCode}</strong> {b.batchName}</>}</span>
          <span><strong className="summary-rate">{rateLabel(b.totals)}</strong> <span className="muted">{b.finalClasses} final class{b.finalClasses === 1 ? "" : "es"}
            {b.classesNotFinal > 0 && ` (${b.classesNotFinal} not final yet)`} · {b.totals.present} present · {b.totals.late} late · {b.totals.absent} absent
            {b.totals.excused ? ` · ${b.totals.excused} excused` : ""}</span></span>
        </li>)}
          {report.data.batches.length === 0 && <li className="dir-empty muted">No classes in this range.</li>}</ul>}
    </section>}
    {appeals && <section className="panel panel-gap" aria-labelledby="appeals-title">
      <h2 id="appeals-title" className="panel-title">APPEALS</h2>
      {!appeals.ok ? <p className="session-error" role="alert">We couldn’t load appeals right now.</p> : <>
        <p className="panel-sub">Students’ appeals against final attendance. Accepting corrects the record; either way the student sees your note.</p>
        <ul className="dir-list">
          {appeals.data.map((a, index) => <li key={a.id} className="dir-course appeal" aria-label={`Appeal ${a.studentNumber} ${a.classTitle}`}>
            <div className="dir-row">
              <div><strong>{a.studentNumber}</strong> {a.studentName}
                <div className="dir-dates muted">{a.classTitle}, {formatInstant(a.classStartsAt, a.timeZone)} · sent {formatInstant(a.createdAt)}</div>
                <blockquote className="appeal-message">{a.message}</blockquote></div>
              <span className={`status ${a.outcome === "ABSENT" ? "off" : "on"}`}>{outcomeLabels[a.outcome]}</span>
            </div>
            <AppealDecisionForm prefix={`appeal-${index}`} appealId={a.id} current={a.outcome} />
          </li>)}
          {appeals.data.length === 0 && <li className="dir-empty muted">No appeals are waiting.</li>}
        </ul>
      </>}
    </section>}
    {engagement && <section className="panel panel-gap" aria-labelledby="engagement-title">
      <h2 id="engagement-title" className="panel-title">ENGAGEMENT</h2>
      {!engagement.ok ? <p className="session-error" role="alert">We couldn’t load engagement settings right now.</p> : <>
        <p className="panel-sub">Reactions and short questions from the student portal, with each student’s consent. It is
          {engagement.data?.enabled ? " on" : " off"}. Switch it on only after the academy has signed off its privacy and consent policy
          (students’ ages, retention). Zoom emoji are anonymous and are never used.</p>
        {owner ? <EngagementSettingsForm settings={engagement.data} /> : <p className="panel-sub">Only an academy owner can switch it on or off.</p>}
      </>}
    </section>}
  </StaffShell>;
}
