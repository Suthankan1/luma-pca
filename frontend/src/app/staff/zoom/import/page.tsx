import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { ImportWebinarForm } from "@/components/staff/ZoomForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACADEMY_TIME_ZONE, batchOptions, formatClassTime, formatWebinarId, isAcademyManager, type CourseRef, type UpcomingWebinar } from "@/lib/staff-forms";

export const metadata = { title: "Import from Zoom · LUMA" };

type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

/**
 * The academy's upcoming webinars, read from Zoom: choose the dates and batches and LUMA creates the classes, each
 * with its webinar ID, so attendance then links and fills in by itself.
 */
export default async function ImportFromZoomPage() {
  const identity = await requirePortal("staff");
  const shell = { active: "sessions" as const, crumb: "Classes", title: "Import classes from Zoom",
    subtitle: "Your upcoming Zoom webinars. Pick the dates and batches; LUMA creates the classes with their webinar ID, so attendance is collected automatically.",
    hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}><p className="banner">Only academy owners and administrators import classes.</p></StaffShell>;
  }
  const [webinars, batches, courses] = await Promise.all([
    staffApi<UpcomingWebinar[]>("/api/v1/zoom/upcoming-webinars"),
    staffApi<Batch[]>("/api/v1/batches"),
    staffApi<CourseRef[]>("/api/v1/courses"),
  ]);
  const options = batches.ok && courses.ok ? batchOptions(courses.data, batches.data, b => b.active) : [];
  return <StaffShell {...shell}>
    <p className="link-row"><Link className="text-link" href="/staff/sessions">← Classes</Link></p>
    {!webinars.ok ? <p className="session-error" role="alert">{webinars.detail ?? "We couldn’t read the webinars from Zoom right now."}</p>
      : webinars.data.length === 0 ? <p className="banner">Zoom lists no upcoming webinars for the academy.</p>
      : webinars.data.map((w, index) => <section key={w.webinarId} className="panel panel-gap" aria-label={`Webinar ${formatWebinarId(w.webinarId)}`}>
        <h2 className="panel-title">{(w.topic ?? "Untitled webinar").toUpperCase()}</h2>
        <p className="panel-sub">Webinar {formatWebinarId(w.webinarId)}{w.recurring ? " · recurring" : ""}{w.timeZone ? ` · ${w.timeZone}` : ""}
          {" "}· {w.runs.length} upcoming date{w.runs.length === 1 ? "" : "s"}</p>
        {w.liveEvents === false && <p className="banner">This webinar is not on the server’s webhook allow-list, so LUMA ignores its
          live events. Attendance will still come from Zoom’s report after each class; ask the operator to add it for live views.</p>}
        {w.runs.length === 0 ? <p className="muted">{w.note}</p>
          : <ImportWebinarForm prefix={`import-${index}`} webinarId={w.webinarId} topic={w.topic ?? ""} batches={options}
            runs={w.runs.map(r => ({ occurrenceId: r.occurrenceId, scheduled: r.classId !== null,
              label: formatClassTime(r.startsAt, r.endsAt, w.timeZone ?? ACADEMY_TIME_ZONE) }))} />}
      </section>)}
  </StaffShell>;
}
