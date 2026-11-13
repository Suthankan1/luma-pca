import Link from "next/link";
import { notFound } from "next/navigation";
import { StaffShell } from "@/components/StaffShell";
import { CancelSeriesForm, EditSeriesForm, EditSessionForm, StatusForm, type SessionDefaults } from "@/components/staff/SessionForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  batchOptions, formatClassTime, formatDate, formatDuration, formatInstant, formatWebinarId, instantParts, isAcademyManager, occurrenceState, uuidOrNull,
  type ClassSession, type CourseRef, type Occurrence, type ProvisionalAttendance,
} from "@/lib/staff-forms";

type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };
type ExpectedStudent = { studentId: string; studentNumber: string; fullName: string; batchIds: string[] };

export const metadata = { title: "Class · LUMA" };

/** Readable by managers, and by assigned staff for classes with one of their batches (they see only their batches and students). */
export default async function SessionPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const identity = await requirePortal("staff");
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) notFound();
  const manager = isAcademyManager(identity.roles);
  const [session, expected, batches, courses, zoom, attendance] = await Promise.all([
    staffApi<ClassSession>(`/api/v1/class-sessions/${sessionId}`),
    staffApi<ExpectedStudent[]>(`/api/v1/class-sessions/${sessionId}/students`),
    manager ? staffApi<Batch[]>("/api/v1/batches") : Promise.resolve(null),
    manager ? staffApi<CourseRef[]>("/api/v1/courses") : Promise.resolve(null),
    manager ? staffApi<Occurrence[]>(`/api/v1/zoom/occurrences?decided=true&sessionId=${sessionId}&limit=50`) : Promise.resolve(null),
    staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${sessionId}/attendance`),
  ]);
  if (!session.ok && (session.status === 404 || session.status === 403)) notFound();
  const shell = { active: "sessions" as const, crumb: "Timetable", hasStudentPortal: hasPortal(identity, "student") };
  if (!session.ok || !expected.ok || (batches && !batches.ok) || (courses && !courses.ok)) {
    return <StaffShell {...shell} title="Class" subtitle="">
      <p className="session-error" role="alert">We couldn’t load this class right now. Please reload the page shortly.</p>
    </StaffShell>;
  }
  const s = session.data;
  const start = instantParts(s.startsAt, s.timeZone);
  const defaults: SessionDefaults = { title: s.title, date: start.date, startTime: start.time, endTime: instantParts(s.endsAt, s.timeZone).time,
    zoomWebinarId: s.zoomWebinarId ?? "", batchIds: s.batches.map(b => b.batchId) };
  const current = new Set(defaults.batchIds);
  // Active batches can be added; batches already on the class stay listed even if since deactivated.
  const options = batches?.ok && courses?.ok ? batchOptions(courses.data, batches.data, b => b.active || current.has(b.id)) : [];
  const batchCode = new Map(s.batches.map(b => [b.batchId, b.code]));
  const cancelled = s.status !== "SCHEDULED";

  return <StaffShell {...shell} title={s.title}
    subtitle={`${formatClassTime(s.startsAt, s.endsAt, s.timeZone)} (${s.timeZone})${cancelled ? " · Cancelled" : ""}`}>
    <p><Link className="text-link" href="/staff/sessions">← Classes</Link></p>
    {cancelled && <p className="banner">This class is cancelled. It stays on record and is not linked to Zoom events automatically.</p>}
    <div className="staff-grid">
      <section className="panel" aria-labelledby="expected-title">
        <h2 id="expected-title" className="panel-title">EXPECTED STUDENTS</h2>
        <p className="panel-sub">Enrolled in {s.batches.length === 1 ? "the batch" : "one of the batches"} on {formatDate(start.date)}; each student once.
          {" "}{expected.data.length} student{expected.data.length === 1 ? "" : "s"}.</p>
        <ul className="dir-list">
          {expected.data.map(student => <li key={student.studentId} className="dir-row dir-course">
            <div>{manager ? <Link className="text-link" href={`/staff/students/${student.studentId}`}><strong>{student.studentNumber}</strong> <span>{student.fullName}</span></Link>
              : <><strong>{student.studentNumber}</strong> <span>{student.fullName}</span></>}</div>
            <span className="dir-dates muted">{student.batchIds.map(id => batchCode.get(id)).filter(Boolean).join(", ")}</span>
          </li>)}
          {expected.data.length === 0 && <li className="dir-empty muted">No students are enrolled in these batches on that date.</li>}
        </ul>
        <h3 className="form-title panel-gap">Batches</h3>
        <ul className="dir-list">{s.batches.map(b => <li key={b.batchId} className="dir-row dir-course">
          <div><strong>{b.code}</strong> <span>{b.name}</span></div>
          <Link className="text-link" href={`/staff/batches/${b.batchId}`}>Roster →</Link>
        </li>)}</ul>
        <p className="panel-sub">Zoom webinar: {s.zoomWebinarId ? formatWebinarId(s.zoomWebinarId) : "not set"}</p>
        {zoom && <div aria-labelledby="zoom-links-title" role="region">
          <h3 id="zoom-links-title" className="form-title">Zoom occurrences linked</h3>
          {!zoom.ok ? <p className="session-error" role="alert">We couldn’t load the Zoom links right now.</p>
            : zoom.data.length === 0 ? <p className="panel-sub">None yet. Links are made when Zoom reports this webinar running around the class time,
              and checked again whenever the class is changed.</p>
            : <ul className="dir-list">{zoom.data.map(o => <li key={o.occurrenceUuid} className="dir-row">
              <span>Started {formatInstant(o.firstEventAt)} · {o.eventCount} event{o.eventCount === 1 ? "" : "s"}</span>
              <span className="status on">{occurrenceState(o)}</span>
            </li>)}</ul>}
          <p className="panel-sub"><Link className="text-link" href="/staff/zoom">Review Zoom occurrences →</Link></p>
        </div>}
      </section>
      {manager && <section className="panel" aria-labelledby="manage-title">
        <h2 id="manage-title" className="panel-title">MANAGE</h2>
        <EditSessionForm session={s} batches={options} defaults={defaults} />
        <StatusForm session={s} defaults={defaults} />
        {s.seriesId && s.status === "SCHEDULED" && <EditSeriesForm sessionId={s.id} seriesId={s.seriesId} fromDate={s.localDate} timeZone={s.timeZone} />}
        {s.seriesId && <CancelSeriesForm sessionId={s.id} seriesId={s.seriesId} fromDate={s.localDate} />}
      </section>}
    </div>
    <section className="panel panel-gap" aria-labelledby="attendance-title">
      <h2 id="attendance-title" className="panel-title">{attendance.ok && attendance.data.status === "RECONCILED" ? "ATTENDANCE FROM ZOOM’S REPORT" : "PROVISIONAL ATTENDANCE"}</h2>
      {!attendance.ok ? <p className="session-error" role="alert">We couldn’t load attendance right now. Please reload shortly.</p> : <>
        <p className="panel-sub">{attendance.data.status === "RECONCILED"
            ? "From Zoom’s participant report, fetched after the webinar ended"
            : attendance.data.reportedOccurrences > 0 ? "Partly from Zoom’s participant report, partly from live Zoom events"
            : "From live Zoom events"}, recalculated each time this page loads ({formatInstant(attendance.data.calculatedAt)}).
          {" "}Time connected counts rejoins once and only within the class time.
          {" "}{attendance.data.status === "RECONCILED" ? "No present or absent decision is made until the academy sets its attendance rule."
            : "This is not a final record: live events can arrive late or not at all, and a student not seen here is not proof of absence."}
          {" "}{attendance.data.linkedOccurrences === 0 ? "No Zoom occurrence is linked to this class yet."
            : attendance.data.webinarEnded ? "The webinar has ended." : "The webinar has not reported an end yet."}
          {manager && attendance.data.unmatchedPeople > 0 && <> {attendance.data.unmatchedPeople} Zoom participant{attendance.data.unmatchedPeople === 1 ? " is" : "s are"} not
            matched to a student; <Link className="text-link" href="/staff/zoom">match them in Zoom review</Link>.</>}</p>
        <ul className="dir-list">
          {attendance.data.students.map(a => <li key={a.studentId} className="dir-row dir-course attendance-row"
            aria-label={`Attendance ${a.studentNumber} ${a.fullName}`}>
            <div><strong>{a.studentNumber}</strong> <span>{a.fullName}</span>
              {!a.expected && <span className="muted"> · not expected at this class</span>}
              <div className="dir-dates">{a.seen
                ? <>{formatDuration(a.connectedSeconds)} connected · first joined {formatInstant(a.firstJoinAt!, s.timeZone)}
                    {a.secondsAfterStart ? ` (${formatDuration(a.secondsAfterStart)} after the start)` : ""}
                    {" "}· {a.visits} visit{a.visits === 1 ? "" : "s"}{a.openVisits > 0 ? " · no leave received yet" : ""}</>
                : <span className="muted">Not seen in Zoom yet</span>}</div>
            </div>
            <span className={`status ${a.seen ? "on" : "off"}`}>{a.seen ? "Seen" : "Not seen"}</span>
          </li>)}
          {attendance.data.students.length === 0 && <li className="dir-empty muted">No students are expected at this class.</li>}
        </ul>
      </>}
    </section>
  </StaffShell>;
}
