import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { ImportWebinarForm } from "@/components/staff/ZoomForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import {
  ACADEMY_TIME_ZONE, batchOptions, formatClassTime, formatInstant, formatWebinarId, isAcademyManager, type CourseRef, type UpcomingWebinar,
  type WebinarSummary,
} from "@/lib/staff-forms";

export const metadata = { title: "Import from Zoom · LUMA" };

type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

/**
 * The academy's upcoming webinars, read from Zoom in one call. Opening one reads its dates; choose the dates and batches
 * and LUMA creates the classes, each with its webinar ID, so attendance then links and fills in by itself.
 */
export default async function ImportFromZoomPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const q = typeof params.q === "string" ? params.q.trim().slice(0, 100) : "";
  const chosen = typeof params.webinar === "string" && /^\d{9,20}$/.test(params.webinar) ? params.webinar : null;
  const shell = { active: "sessions" as const, crumb: "Classes", title: "Import classes from Zoom",
    subtitle: "Your upcoming Zoom webinars. Open one, pick the dates and batches; LUMA creates the classes with their webinar ID, so attendance is collected automatically.",
    hasStudentPortal: hasPortal(identity, "student") };
  if (!isAcademyManager(identity.roles)) {
    return <StaffShell {...shell}><p className="banner">Only academy owners and administrators import classes.</p></StaffShell>;
  }
  const [list, detail, batches, courses] = await Promise.all([
    chosen ? Promise.resolve(null) : staffApi<WebinarSummary[]>(`/api/v1/zoom/upcoming-webinars${q ? `?${new URLSearchParams({ q })}` : ""}`),
    chosen ? staffApi<UpcomingWebinar>(`/api/v1/zoom/upcoming-webinars/${chosen}`) : Promise.resolve(null),
    staffApi<Batch[]>("/api/v1/batches"),
    staffApi<CourseRef[]>("/api/v1/courses"),
  ]);
  const options = batches.ok && courses.ok ? batchOptions(courses.data, batches.data, b => b.active) : [];

  if (detail) {
    const back = <p className="link-row"><Link className="text-link" href="/staff/zoom/import">← All upcoming webinars</Link></p>;
    if (!detail.ok) {
      return <StaffShell {...shell}>{back}<p className="session-error" role="alert">
        {detail.status === 404 ? "Zoom has no such webinar in the academy’s account." : detail.detail ?? "We couldn’t read this webinar from Zoom right now."}</p></StaffShell>;
    }
    const w = detail.data;
    return <StaffShell {...shell}>{back}
      <section className="panel" aria-label={`Webinar ${formatWebinarId(w.webinarId)}`}>
        <h2 className="panel-title">{(w.topic ?? "Untitled webinar").toUpperCase()}</h2>
        <p className="panel-sub">Webinar {formatWebinarId(w.webinarId)}{w.recurring ? " · recurring" : ""}{w.timeZone ? ` · ${w.timeZone}` : ""}
          {" "}· {w.runs.length} upcoming date{w.runs.length === 1 ? "" : "s"} in the next 120 days</p>
        {w.liveEvents === false && <p className="banner">This webinar is not on the server’s webhook allow-list, so LUMA ignores its
          live events. Attendance will still come from Zoom’s report after each class; ask the operator to add it for live views.</p>}
        {w.runs.length === 0 ? <p className="muted">{w.note}</p>
          : <ImportWebinarForm prefix="import" webinarId={w.webinarId} topic={w.topic ?? ""} batches={options}
            runs={w.runs.map(r => ({ occurrenceId: r.occurrenceId, scheduled: r.classId !== null,
              label: formatClassTime(r.startsAt, r.endsAt, w.timeZone ?? ACADEMY_TIME_ZONE) }))} />}
      </section>
    </StaffShell>;
  }

  return <StaffShell {...shell}>
    <p className="link-row"><Link className="text-link" href="/staff/sessions">← Classes</Link></p>
    <form method="get" className="export-range" role="search" aria-label="Find a webinar">
      <label htmlFor="webinar-search">Find a webinar</label>
      <input id="webinar-search" name="q" defaultValue={q} maxLength={100} placeholder="Topic or webinar ID" autoComplete="off" />
      <button className="action secondary" type="submit">Search</button>
    </form>
    {!list?.ok ? <p className="session-error" role="alert">{list?.detail ?? "We couldn’t read the webinars from Zoom right now."}</p>
      : <section className="panel panel-gap" aria-labelledby="upcoming-title">
        <h2 id="upcoming-title" className="panel-title">UPCOMING WEBINARS</h2>
        <p className="panel-sub">{list.data.length} webinar{list.data.length === 1 ? "" : "s"}{q ? ` matching “${q}”` : ""}, soonest first.</p>
        <ul className="dir-list">{list.data.map(w => <li key={w.webinarId} className="dir-row" aria-label={`Webinar ${formatWebinarId(w.webinarId)}`}>
          <span><Link className="text-link" href={`/staff/zoom/import?webinar=${w.webinarId}`}><strong>{w.topic ?? "Untitled webinar"}</strong></Link>
            <span className="dir-dates muted">{formatWebinarId(w.webinarId)}{w.recurring ? " · recurring" : ""}
              {w.nextStart ? ` · next ${formatInstant(w.nextStart, w.timeZone ?? ACADEMY_TIME_ZONE)}` : " · no fixed time"}</span></span>
          <span className={`status ${w.upcomingClasses > 0 ? "on" : "off"}`}>
            {w.upcomingClasses > 0 ? `${w.upcomingClasses} class${w.upcomingClasses === 1 ? "" : "es"} in LUMA` : "Not in LUMA yet"}</span>
        </li>)}
          {list.data.length === 0 && <li className="dir-empty muted">{q ? "No upcoming webinar matches." : "Zoom lists no upcoming webinars for the academy."}</li>}</ul>
      </section>}
  </StaffShell>;
}
