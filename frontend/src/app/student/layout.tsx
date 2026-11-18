import type { ReactNode } from "react";
import { requirePortal } from "@/lib/auth-server";

/** The sign-in check for every student page, before any loading screen streams (see the staff layout). */
export default async function StudentLayout({ children }: { children: ReactNode }) {
  await requirePortal("student");
  return children;
}
