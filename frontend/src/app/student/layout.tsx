import type { ReactNode } from "react";
import { requirePortal } from "@/lib/auth-server";
import { PortalRailProvider } from "@/components/PortalRail";

/** The sign-in check for every student page, before any loading screen streams (see the staff layout). */
export default async function StudentLayout({ children }: { children: ReactNode }) {
  await requirePortal("student");
  return <PortalRailProvider>{children}</PortalRailProvider>;
}
