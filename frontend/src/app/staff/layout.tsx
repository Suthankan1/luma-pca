import type { ReactNode } from "react";
import { requirePortal } from "@/lib/auth-server";

/**
 * The sign-in check for every staff page, before any loading screen streams, so a signed-out visitor still gets a real
 * redirect. The sealed cookie supplies the access token for the request-scoped identity check against the API.
 */
export default async function StaffLayout({ children }: { children: ReactNode }) {
  await requirePortal("staff");
  return children;
}
