import { redirect } from "next/navigation";

/** Retain old bookmarks after retiring the standalone report dashboard. */
export default function LegacyDashboard() {
  redirect("/staff/reports");
}
