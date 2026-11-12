import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { ScheduleForm, SeriesForm } from "@/components/staff/SessionForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  batchOptions, formatClassTime, formatDate, isAcademyManager, sessionListHref, sessionWindow, uuidOrNull, type ClassSession, type CourseRef,
} from "@/lib/staff-forms";

type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export const metadata = { title: "Classes · LUMA" };

export default async function SessionsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const window = sessionWindow(params.from);
  const batchFilter = uuidOrNull(typeof params.batch === "string" ? params.batch : null);
  const manager = isAcademyManager(identity.roles);
  const shell = { active: "sessions" as const, crumb: "Timetable", title: "Classes",
    subtitle: "A class can be shared by several batches; each student is expected once. Classes are cancelled, never deleted.",
    hasStudentPortal: hasPortal(identity, "student") };

  const query = new URLSearchParams({ from: window.fromInstant, to: window.toInstant });
  if (batchFilter) query.set("batchId", batchFilter);
  const [sessions, batches, courses] = await Promise.all([
    staffApi<ClassSession[]>(`/api/v1/class-sessions?${query}`),
    // Instructors and attendance officers get only their assigned batches; analysts have no batch access.
    staffApi<Batch[]>("/api/v1/batches"),
    manager ? staffApi<CourseRef[]>("/api/v1/courses") : Promise.resolve(null),
  ]);
  if (!sessions.ok && sessions.status === 403) {
    return <StaffShell {...shell}>
      <p className="banner">Classes are visible to academy owners and administrators, and to instructors and attendance officers for their assigned batches.</p>
    </StaffShell>;
  }
  if (!sessions.ok || !batches.ok || (courses && !courses.ok)) {
    // An unknown or unreadable batch filter is refused by the API like any other request.
    const message = batchFilter && !sessions.ok && sessions.status === 404 ? "That batch is not available to you." : "We couldn’t load classes right now. Please reload the page shortly.";
    return <StaffShell {...shell}>
      <p className="session-error" role="alert">{message}</p>
      <p><Link className="text-link" href="/staff/sessions">Show all classes →</Link></p>
    </StaffShell>;
  }
  const filterOptions = batchOptions(courses?.ok ? courses.data : [], batches.data, () => true);
  const scheduleOptions = courses?.ok ? batchOptions(courses.data, batches.data, batch => batch.active) : [];

  return <StaffShell {...shell}>
    {!manager && <p className="banner">Only academy owners and administrators schedule classes. You see classes for the batches you are assigned to.</p>}
    <div className={manager ? "staff-grid" : undefined}>
      <section className="panel" aria-labelledby="classes-title">
        <h2 id="classes-title" className="panel-title">CLASSES</h2>
        <p className="panel-sub">{formatDate(window.from)} – {formatDate(window.lastDay)} · Sri Lanka time · {sessions.data.length} class{sessions.data.length === 1 ? "" : "es"}</p>
        <nav className="pager" aria-label="Fortnight">
          <Link className="text-link" href={sessionListHref({ from: window.previous, batch: batchFilter })}>← Previous two weeks</Link>
          <Link className="text-link" href={sessionListHref({ batch: batchFilter })}>From today</Link>
          <Link className="text-link" href={sessionListHref({ from: window.next, batch: batchFilter })}>Next two weeks →</Link>
        </nav>
        <form className="filter-form" method="get" action="/staff/sessions">
          <input type="hidden" name="from" value={window.from} />
          <label htmlFor="class-batch">Batch</label>
          <select id="class-batch" name="batch" defaultValue={batchFilter ?? ""}>
            <option value="">All {manager ? "batches" : "my batches"}</option>
            {filterOptions.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
          </select>
          <button className="action secondary" type="submit">Show</button>
        </form>
        <ul className="dir-list">
          {sessions.data.map(session => <li key={session.id} className="dir-row dir-course">
            <div>
              <Link className="text-link" href={`/staff/sessions/${session.id}`}><strong>{session.title}</strong></Link>
              <span className="dir-dates muted"> {formatClassTime(session.startsAt, session.endsAt, session.timeZone)}</span>
              <div className="dir-dates muted">{session.batches.map(batch => batch.code).join(", ")}</div>
            </div>
            <span className={`status ${session.status === "SCHEDULED" ? "on" : "off"}`}>{session.status === "SCHEDULED" ? "Scheduled" : "Cancelled"}</span>
          </li>)}
          {sessions.data.length === 0 && <li className="dir-empty muted">No classes in these two weeks.</li>}
        </ul>
      </section>
      {manager && <section className="panel" aria-labelledby="new-class-title">
        <h2 id="new-class-title" className="panel-title">NEW CLASS</h2>
        <ScheduleForm batches={scheduleOptions} />
        <SeriesForm batches={scheduleOptions} />
      </section>}
    </div>
  </StaffShell>;
}
