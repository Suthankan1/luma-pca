import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { AttendanceGrid } from "@/components/insights/AttendanceGrid";
import { ClassTable } from "@/components/insights/ClassTable";
import { Kpis } from "@/components/insights/Kpis";
import { RangeFilter } from "@/components/insights/RangeFilter";
import { StudentTable } from "@/components/insights/StudentTable";
import { AttendanceTabs } from "@/components/staff/AttendanceTabs";
import { requirePortal } from "@/lib/auth-server";
import { dateOrNull, formatDuration, level, presets, withParams, withoutCells, type Dashboard } from "@/lib/insights";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { academyToday, uuidOrNull } from "@/lib/staff-forms";

type Course = { id: string; code: string; title: string; active: boolean };
type Batch = { id: string; courseId: string; code: string; name: string; active: boolean };

export const metadata = { title: "Attendance dashboard · LUMA" };

const views = [["students", "Students"], ["classes", "Classes"], ["grid", "Grid"]] as const;

export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const one = (key: string) => typeof params[key] === "string" ? params[key] as string : null;
  const today = academyToday();
  const fallback = presets(today)[1];
  let from = dateOrNull(one("from")) ?? fallback.from;
  let to = dateOrNull(one("to")) ?? fallback.to;
  if (from > to) [from, to] = [to, from];
  const courseId = uuidOrNull(one("course"));
  const batchId = uuidOrNull(one("batch"));
  const view = views.some(([key]) => key === one("view")) ? one("view")! : "students";
  const shell = { active: "dashboard" as const, crumb: "Attendance", title: "Attendance reports",
    subtitle: "Who attended, for how long, and how often they left and rejoined. Choose a course or batch and a period.",
    hasStudentPortal: hasPortal(identity, "student") };

  const query = withParams("/api/v1/attendance/dashboard", { from, to, courseId, batchId }) as `/api/v1/${string}`;
  const [dashboard, courses, batches] = await Promise.all([
    staffApi<Dashboard>(query, undefined, 30000),
    staffApi<Course[]>("/api/v1/courses"),
    staffApi<Batch[]>("/api/v1/batches"),
  ]);

  const courseList = courses.ok ? courses.data : [];
  const batchList = batches.ok ? batches.data : [];
  const courseCode = new Map(courseList.map(c => [c.id, c.code]));
  const keep = { course: courseId, batch: batchId, view: view === "students" ? null : view };
  const tabs = <AttendanceTabs active="reports" />;
  const filters = <section className="panel filters-panel" aria-labelledby="filters-title">
    <h2 id="filters-title" className="sr-only">Filters</h2>
    <form method="get" action="/staff/dashboard" className="filters" role="search" aria-label="Filter attendance">
      <input type="hidden" name="from" value={from} />
      <input type="hidden" name="to" value={to} />
      {view !== "students" && <input type="hidden" name="view" value={view} />}
      <div className="field">
        <label htmlFor="filter-course">Course</label>
        <select id="filter-course" name="course" defaultValue={courseId ?? ""}>
          <option value="">All courses</option>
          {courseList.map(c => <option key={c.id} value={c.id}>{c.code} · {c.title}{c.active ? "" : " (inactive)"}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="filter-batch">Batch</label>
        <select id="filter-batch" name="batch" defaultValue={batchId ?? ""}>
          <option value="">All batches</option>
          {batchList.filter(b => !courseId || b.courseId === courseId).map(b =>
            <option key={b.id} value={b.id}>{courseCode.get(b.courseId) ? `${courseCode.get(b.courseId)} · ` : ""}{b.code} · {b.name}</option>)}
        </select>
      </div>
      <button className="action" type="submit">Apply</button>
      {(courseId || batchId) && <Link className="text-link" href={withParams("/staff/dashboard", { from, to })}>Clear</Link>}
    </form>
    <RangeFilter path="/staff/dashboard" from={from} to={to} today={today} keep={keep} />
  </section>;

  if (!dashboard.ok) {
    return <StaffShell {...shell}>{tabs}{filters}
      <p className="session-error" role="alert">{dashboard.status === 400 ? dashboard.detail ?? "Choose a shorter period (at most 92 days)."
        : "We couldn’t load attendance right now. Please reload the page shortly."}</p>
    </StaffShell>;
  }
  const d = dashboard.data;
  const t = d.totals;
  const studentHref = Object.fromEntries(d.students.map(s =>
    [s.studentId, withParams(`/staff/students/${s.studentId}/attendance`, { from, to })]));
  const rows = d.students.map(withoutCells);

  return <StaffShell {...shell}>
    {tabs}
    {filters}
    <Kpis label="Totals" items={[
      { label: "Classes held", value: String(t.classes), note: t.classes ? `${formatDuration(t.classSeconds)} of webinars` : undefined },
      { label: "Students", value: String(t.students) },
      { label: "Average attendance", value: `${t.averagePercent}%`, note: t.minPercent ? `rule: ${t.minPercent}% of the class` : "share of class time",
        tone: t.students ? level(t.averagePercent, t.minPercent) as "good" | "warn" | "bad" : undefined },
      { label: `Below ${t.minPercent ?? 75}%`, value: String(t.belowThreshold || d.students.filter(s => s.percent < (t.minPercent ?? 75)).length),
        tone: d.students.some(s => s.percent < (t.minPercent ?? 75)) ? "warn" : undefined },
      ...(t.connectedNow ? [{ label: "Connected now", value: String(t.connectedNow), tone: "good" as const }] : []),
    ]} />
    {d.truncated && <p className="banner">Showing the latest classes only. Choose a shorter period or one batch to see them all.</p>}
    <section className="panel" aria-labelledby="results-title">
      <div className="panel-head">
        <h2 id="results-title" className="panel-title">{views.find(([k]) => k === view)![1].toUpperCase()}</h2>
        <nav className="tabs" aria-label="View">
          {views.map(([key, label]) => <Link key={key} className={`tab ${view === key ? "on" : ""}`} aria-current={view === key ? "page" : undefined}
            href={withParams("/staff/dashboard", { from, to, course: courseId, batch: batchId, view: key === "students" ? null : key })}>{label}</Link>)}
        </nav>
      </div>
      {view === "students" && (d.students.length === 0
        ? <p className="muted">No students were expected at classes in this period.</p>
        : <StudentTable rows={rows} minPercent={t.minPercent} studentHref={studentHref} />)}
      {view === "classes" && <ClassTable classes={d.classes} minPercent={t.minPercent} />}
      {view === "grid" && <AttendanceGrid classes={d.classes} students={d.students} minPercent={t.minPercent} studentHref={studentHref} />}
      {batchId && <p className="link-row"><Link className="text-link" href={`/staff/batches/${batchId}`}>Batch roster and CSV export →</Link></p>}
    </section>
  </StaffShell>;
}
