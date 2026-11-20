import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { ACTIVITY_CATEGORIES, activityHref, actorName, describeActivity, type ActivityEvent } from "@/lib/activity";
import { formatInstant, uuidOrNull } from "@/lib/staff-forms";

type Page = { items: ActivityEvent[]; nextBefore: string | null };

export const metadata = { title: "Activity log · LUMA" };

export default async function ActivityPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const identity = await requirePortal("staff");
  const params = await searchParams;
  const category = ACTIVITY_CATEGORIES.find(c => c.key === params.category)?.key ?? "";
  const target = uuidOrNull(typeof params.target === "string" ? params.target : null);
  const before = typeof params.before === "string" && /^\d{1,19}_[0-9a-f-]{36}$/.test(params.before) ? params.before : null;
  const shell = { active: "activity" as const, crumb: "Oversight", title: "Activity log",
    subtitle: "Who changed what, newest first. The log cannot be edited or deleted, and never holds passwords or links.",
    hasStudentPortal: hasPortal(identity, "student") };
  const query = new URLSearchParams({ limit: "50" });
  if (category) query.set("category", category);
  if (target) query.set("targetId", target);
  if (before) query.set("before", before);
  const result = await staffApi<Page>(`/api/v1/audit-events?${query}`);

  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="activity-title">
      <h2 id="activity-title" className="panel-title">ACTIVITY</h2>
      <nav className="filter-row" aria-label="Filter activity">
        {ACTIVITY_CATEGORIES.map(c => <Link key={c.key || "all"} href={activityHref({ category: c.key, target })}
          className={`chip ${c.key === category ? "cyan" : ""}`} aria-current={c.key === category ? "true" : undefined}>{c.label}</Link>)}
      </nav>
      {target && <p className="panel-sub">Showing one record’s history. <Link className="text-link" href={activityHref({ category })}>Show everything</Link></p>}
      {!result.ok ? <p className="session-error" role="alert">We couldn’t load the activity log right now. Please reload the page shortly.</p> : <>
        <ul className="dir-list activity-list">
          {result.data.items.map(event => {
            const { summary, detail } = describeActivity(event);
            return <li key={event.id} className="dir-row dir-course" aria-label={`${summary}, ${formatInstant(event.occurredAt)}`}>
              <div>
                <strong>{summary}</strong>
                {detail && <div className="dir-dates muted">{detail}</div>}
                <div className="dir-dates muted">by {actorName(event)}</div>
              </div>
              <span className="status">{formatInstant(event.occurredAt)}</span>
            </li>;
          })}
          {result.data.items.length === 0 && <li className="dir-empty muted">{before ? "No older activity." : "Nothing recorded here yet."}</li>}
        </ul>
        <div className="nav-links">
          {before && <Link className="action secondary" href={activityHref({ category, target })}>← Newest</Link>}
          {result.data.nextBefore && <Link className="action secondary" href={activityHref({ category, target, before: result.data.nextBefore })}>Older →</Link>}
        </div>
      </>}
    </section>
  </StaffShell>;
}
