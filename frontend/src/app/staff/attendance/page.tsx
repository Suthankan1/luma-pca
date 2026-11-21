import { StaffShell } from "@/components/StaffShell";
import { AttendanceTabs, type AttendanceSection } from "@/components/staff/AttendanceTabs";
import { AppealDecisionForm, FinaliseAllForm, PolicyForm } from "@/components/staff/AttendanceForms";
import { EngagementSettingsForm } from "@/components/staff/EngagementForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import Link from "next/link";
import { academyToday, formatInstant, monthStart, outcomeLabels, rateLabel, type AcademyAttendanceSummary, type AttendanceAppeal, type PendingAttendance, pendingStateLabels, type AttendancePolicy, type EngagementSettings } from "@/lib/staff-forms";

export const metadata = { title: "Attendance · LUMA" };

/** Attendance work for staff: what needs doing, batch totals, and the academy's rule and engagement settings. */
export default async function AttendancePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const shell = { active: "attendance" as const, crumb: "Teaching", title: "Attendance",
    subtitle: "Finalise ended classes, answer appeals, see reports, and keep the academy’s attendance rule.",
    hasStudentPortal: hasPortal(identity, "student") };
  const query = await searchParams;
  // Staff start with what needs doing.
  const section: AttendanceSection = query.section === "totals" || query.section === "settings" ? query.section : "todo";
  const isoDate = (v: unknown) => typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : null;
  const reportFrom = isoDate(query.from) ?? monthStart(academyToday());
  const reportTo = isoDate(query.to) ?? academyToday();
  const [policy, appeals, engagement, report, pending] = await Promise.all([
    staffApi<AttendancePolicy | null>("/api/v1/attendance-policy"),
    staffApi<AttendanceAppeal[]>("/api/v1/attendance-appeals?status=OPEN"),
    section === "settings" ? staffApi<EngagementSettings | null>("/api/v1/engagement-settings") : Promise.resolve(null),
    section === "totals"
      ? staffApi<AcademyAttendanceSummary>(`/api/v1/attendance/summary?${new URLSearchParams({ from: reportFrom, to: reportTo })}`)
      : Promise.resolve(null),
    staffApi<PendingAttendance>("/api/v1/attendance/pending"),
  ]);
  const todoCount = (pending.ok ? pending.data.classes.filter(c => c.state === "READY").length : 0) + (appeals.ok ? appeals.data.length : 0);
  if (!policy.ok) {
    return <StaffShell {...shell}><p className="session-error" role="alert">
      We couldn’t load the attendance rule right now. Please reload shortly.</p></StaffShell>;
  }
  const rule = policy.data;
  return <StaffShell {...shell}>
    <AttendanceTabs active={section} todoCount={todoCount} />
    {section === "todo" && <p className="panel-sub rule-line">{rule
      ? <>Rule: present from {rule.minConnectedPercent}% of the class, late after {rule.lateAfterMinutes} min.</>
      : <>No attendance rule is recorded yet, so classes can’t be finalised.</>}{" "}
      <Link className="text-link" href="/staff/attendance?section=settings">{rule ? "Rule & settings" : "Record the rule"} →</Link></p>}
    {section === "settings" && <section className="panel" aria-labelledby="rule-title">
      <h2 id="rule-title" className="panel-title">ATTENDANCE RULE</h2>
      {rule ? <>
        <p className="rule-summary"><strong>Present</strong> when connected for at least {rule.minConnectedPercent}% of the class time;
          {" "}<strong>late</strong> when also first joining more than {rule.lateAfterMinutes} minute{rule.lateAfterMinutes === 1 ? "" : "s"} after
          {" "}the start; otherwise <strong>absent</strong>.</p>
        <p className="panel-sub">{rule.description} · last changed {formatInstant(rule.updatedAt)}. Classes finalised earlier keep the rule they used.</p>
      </> : <p className="banner">No rule has been recorded yet, so no class can be finalised. The rule is the academy’s decision; LUMA only applies it.</p>}
      <PolicyForm policy={rule} />
    </section>}
    {section === "todo" && <section className="panel" aria-labelledby="pending-title">
      <h2 id="pending-title" className="panel-title">WAITING TO BE FINALISED</h2>
      {!pending.ok ? <p className="session-error" role="alert">We couldn’t load the classes waiting to be finalised.</p> : <>
        <p className="panel-sub">Classes of the last 60 days that have ended but are not final.
          {pending.data.autoFinalise ? " Automatic finalisation is on: ready classes are finalised within a few minutes."
            : " Finalise every ready class at once, or open a class to check it first."}</p>
        <ul className="dir-list">{pending.data.classes.map(c => <li key={c.sessionId} className="dir-row" aria-label={`Pending ${c.title}`}>
          <span><Link className="text-link" href={`/staff/sessions/${c.sessionId}`}><strong>{c.title}</strong></Link>
            {" "}<span className="muted">{formatInstant(c.startsAt, c.timeZone)}</span></span>
          <span className={`status ${c.state === "READY" ? "on" : "off"}`}>{pendingStateLabels[c.state]}</span>
        </li>)}
          {pending.data.classes.length === 0 && <li className="dir-empty muted">Every ended class is final.</li>}</ul>
        {pending.data.classes.some(c => c.state === "NO_ZOOM_RUN") && <p className="panel-sub">A class with no Zoom run: link its run in
          {" "}<Link className="text-link" href="/staff/zoom">Zoom review</Link> or with “Check a webinar”.</p>}
        {pending.data.ruleRecorded ? <FinaliseAllForm ready={pending.data.classes.filter(c => c.state === "READY").length} />
          : <p className="panel-sub">Record the attendance rule first.</p>}
      </>}
    </section>}
    {section === "totals" && report && <section className="panel" aria-labelledby="report-title">
      <h2 id="report-title" className="panel-title">ATTENDANCE BY BATCH</h2>
      <p className="panel-sub">Final attendance totals per batch, without student names. Rate = (present + late) ÷ (present + late + absent);
        excused classes are left out.</p>
      <form method="get" className="export-range" aria-label="Report range">
        <input type="hidden" name="section" value="totals" />
        <label htmlFor="report-from">From</label>
        <input id="report-from" name="from" type="date" required defaultValue={reportFrom} />
        <label htmlFor="report-to">To</label>
        <input id="report-to" name="to" type="date" required defaultValue={reportTo} />
        <button className="action secondary" type="submit">Show report</button>
      </form>
      {!report.ok ? <p className="session-error" role="alert">{report.detail ?? "We couldn’t load the report right now."}</p>
        : <ul className="dir-list">{report.data.batches.map(b => <li key={b.batchId} className="dir-row" aria-label={`Batch ${b.courseCode} ${b.batchCode}`}>
          <span><Link className="text-link" href={`/staff/batches/${b.batchId}?sumFrom=${reportFrom}&sumTo=${reportTo}`}>
            <strong>{b.courseCode} · {b.batchCode}</strong> {b.batchName}</Link></span>
          <span><strong className="summary-rate">{rateLabel(b.totals)}</strong> <span className="muted">{b.finalClasses} final class{b.finalClasses === 1 ? "" : "es"}
            {b.classesNotFinal > 0 && ` (${b.classesNotFinal} not final yet)`} · {b.totals.present} present · {b.totals.late} late · {b.totals.absent} absent
            {b.totals.excused ? ` · ${b.totals.excused} excused` : ""}</span></span>
        </li>)}
          {report.data.batches.length === 0 && <li className="dir-empty muted">No classes in this range.</li>}</ul>}
    </section>}
    {section === "todo" && <section className="panel panel-gap" aria-labelledby="appeals-title">
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
    {section === "settings" && engagement && <section className="panel panel-gap" aria-labelledby="engagement-title">
      <h2 id="engagement-title" className="panel-title">ENGAGEMENT</h2>
      {!engagement.ok ? <p className="session-error" role="alert">We couldn’t load engagement settings right now.</p> : <>
        <p className="panel-sub">Reactions and short questions from the student portal, with each student’s consent. It is
          {engagement.data?.enabled ? " on" : " off"}. Switch it on only after the academy has signed off its privacy and consent policy
          (students’ ages, retention). Zoom emoji are anonymous and are never used.</p>
        <EngagementSettingsForm settings={engagement.data} />
      </>}
    </section>}
  </StaffShell>;
}
