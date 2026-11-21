import type { ReactNode } from "react";
import { requirePortal } from "@/lib/auth-server";
import { PortalFrame, PortalRail, PortalRailProvider } from "@/components/PortalRail";
import { StudentTabLinks } from "@/components/StudentTabLinks";
import { hasPortal } from "@/lib/session-policy";

/** The sign-in check for every student page, before any loading screen streams (see the staff layout). */
export default async function StudentLayout({ children }: { children: ReactNode }) {
  const identity = await requirePortal("student");
  return <PortalRailProvider>
    <PortalFrame className="student-shell">
      <PortalRail portal="student"><StudentTabLinks hasStaffPortal={hasPortal(identity, "staff")} /></PortalRail>
      <main className="content staff-content">{children}</main>
    </PortalFrame>
  </PortalRailProvider>;
}
