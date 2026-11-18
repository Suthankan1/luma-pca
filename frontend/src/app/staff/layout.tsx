import type { ReactNode } from "react";
import { requirePortal } from "@/lib/auth-server";

/**
 * The sign-in check for every staff page, before any loading screen streams, so a signed-out visitor still gets a real
 * redirect. Identity comes from the sealed session cookie, so this costs no backend call; the API checks every request.
 */
export default async function StaffLayout({ children }: { children: ReactNode }) {
  await requirePortal("staff");
  return children;
}
