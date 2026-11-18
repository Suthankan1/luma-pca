import { formatClock, formatDay, formatDuration, level, outcomeLabels, stateLabels, type StudentOverview } from "@/lib/insights";
import { formatDate } from "@/lib/staff-forms";
import { Kpis, PercentBar } from "./Kpis";
import { VisitTimeline } from "./VisitTimeline";

/**
 * One student's attendance (the staff view of a student, and a student's own page): courses and batches, totals, and
 * every class with each join, leave and rejoin. {@code self} words it for the student.
 */
export function AttendanceOverview({ overview, self }: { overview: StudentOverview; self: boolean }) {
  const o = overview.overall;
  const target = overview.minPercent;
  const finals = o.present + o.late + o.absent + o.excused;
  return <>
    <Kpis label={self ? "My attendance" : "Student attendance"} items={[
      { label: "Attendance", value: `${o.percent}%`, note: target ? `the academy’s rule is ${target}%` : "share of class time connected",
        tone: o.classes ? level(o.percent, target) as "good" | "warn" | "bad" : undefined },
      { label: "Time in webinars", value: formatDuration(o.connectedSeconds), note: `of ${formatDuration(o.classSeconds)} of classes` },
      { label: "Classes joined", value: `${o.joined} / ${o.classes}` },
      { label: "Rejoins", value: String(o.rejoins), note: "times left and came back" },
      ...(finals ? [{ label: "Present · Late · Absent", value: `${o.present} · ${o.late} · ${o.absent}`,
        note: o.excused ? `${o.excused} excused` : "final records" }] : []),
    ]} />

    <div className="staff-grid insights-grid">
      <section className="panel" aria-labelledby="classes-heading">
        <h2 id="classes-heading" className="panel-title">CLASSES</h2>
        <p className="panel-sub">{overview.classes.length === 0 ? "No classes held in this period."
          : "Newest first. Open a class to see each time " + (self ? "you" : "the student") + " joined, left and rejoined."}</p>
        <ul className="class-cards">
          {overview.classes.map(c => <li key={c.id}>
            <details className="class-card">
              <summary aria-label={`${c.title}, ${formatDay(c.startsAt, c.timeZone)}`}>
                <span className="class-card-main">
                  <strong>{c.title}</strong>
                  <span className="muted"> {formatDay(c.startsAt, c.timeZone)} · {formatClock(c.startsAt, c.timeZone)}–{formatClock(c.endsAt, c.timeZone)}
                    {c.batchCodes.length ? ` · ${c.batchCodes.join(", ")}` : ""}</span>
                </span>
                <span className="class-card-facts">
                  <span>{formatDuration(c.connectedSeconds)} <span className="muted">of {formatDuration(c.classSeconds)}</span></span>
                  <PercentBar percent={c.percent} tone={level(c.percent, target, c.visits > 0)} />
                  {c.rejoins > 0 && <span className="pill">{c.rejoins} rejoin{c.rejoins === 1 ? "" : "s"}</span>}
                  {c.connectedNow && <span className="pill pill-live">Connected now</span>}
                  <span className={`state state-${(c.outcome ? "final" : c.state).toLowerCase()}`}>
                    {c.outcome ? outcomeLabels[c.outcome] ?? c.outcome : stateLabels[c.state] ?? c.state}</span>
                </span>
              </summary>
              <div className="class-card-body">
                {c.state === "NO_WEBINAR" ? <p className="muted">This class has no Zoom webinar linked, so there is no attendance.</p>
                  : <VisitTimeline startsAt={c.startsAt} endsAt={c.endsAt} timeZone={c.timeZone} visits={c.timeline} />}
                {c.secondsAfterStart !== null && c.secondsAfterStart > 60 &&
                  <p className="muted">First joined {formatDuration(c.secondsAfterStart)} after the start.</p>}
                {!c.outcome && c.state !== "NO_WEBINAR" && <p className="muted">Not final yet: {c.state === "REPORT"
                  ? "from Zoom’s participant report, waiting to be finalised." : "from live Zoom events, checked against Zoom’s report after the webinar."}</p>}
              </div>
            </details>
          </li>)}
        </ul>
      </section>

      <div>
        <section className="panel" aria-labelledby="courses-heading">
          <h2 id="courses-heading" className="panel-title">{self ? "MY COURSES" : "COURSES AND BATCHES"}</h2>
          <ul className="dir-list">
            {overview.enrolments.map(e => {
              const totals = overview.byBatch.find(b => b.batchId === e.batchId);
              return <li key={e.batchId} className="dir-row dir-course" aria-label={`${e.courseCode ?? ""} ${e.batchCode}`}>
                <div>
                  <strong>{e.courseCode ?? "Course"}</strong> <span>{e.courseTitle}</span>
                  <p className="panel-sub">{e.batchCode} · {e.batchName} · {e.status === "WITHDRAWN"
                    ? `withdrawn ${formatDate(e.withdrawnOn)}` : `since ${formatDate(e.enrolledOn)}`}</p>
                </div>
                {totals ? <span className="dir-meta"><PercentBar percent={totals.percent} tone={level(totals.percent, target, totals.joined > 0)} />
                  <span className="muted">{totals.joined}/{totals.classes} classes</span></span>
                  : <span className="muted">no classes in this period</span>}
              </li>;
            })}
            {overview.enrolments.length === 0 && <li className="dir-empty muted">Not enrolled in any batch.</li>}
          </ul>
        </section>
        <section className="panel panel-gap" aria-labelledby="how-heading">
          <h2 id="how-heading" className="panel-title">HOW IT IS COUNTED</h2>
          <ul className="panel-sub how-list">
            <li>Time counts only between the class’s start and end. If {self ? "you" : "the student"} left and rejoined, the times are added; overlapping
              connections are never counted twice.</li>
            <li>Attendance is the share of class time connected.{target ? ` The academy’s rule: ${target}% or more is present.` : ""}</li>
            <li>“Final” classes use Zoom’s own participant report and the academy’s rule. Before that, numbers can still change.</li>
          </ul>
        </section>
      </div>
    </div>
  </>;
}
