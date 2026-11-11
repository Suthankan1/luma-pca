import { Shell } from "@/components/Shell";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal, type Portal } from "@/lib/session-policy";

export async function PortalHome({ portal }: { portal: Portal }) {
  const identity = await requirePortal(portal);
  const other = portal === "staff" ? "student" : "staff";
  return <Shell active={portal} otherPortal={hasPortal(identity, other) ? other : undefined}>
    <h1 className="heading">{portal === "staff" ? "Staff portal" : "Student portal"}</h1>
    <p className="sub">You’re signed in to Physics Cube Academy.</p>
    <p className="banner">{portal === "staff"
      ? "Class schedules and attendance review will appear here when available."
      : "Your class schedule and attendance history will appear here when available."}</p>
    <form method="post" action="/api/session/logout">
      <button className="action secondary" type="submit">Sign out</button>
    </form>
  </Shell>;
}
