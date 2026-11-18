"use client";

import { usePathname } from "next/navigation";
import { StaffSkeleton } from "@/components/StaffSkeleton";
import type { StaffSection } from "@/components/StaffShell";

const sections: Record<string, { active: StaffSection; crumb: string; title: string }> = {
  "": { active: "overview", crumb: "Operations", title: "Staff portal" },
  academy: { active: "academy", crumb: "Academy database", title: "Academy classes" },
  courses: { active: "courses", crumb: "Academic setup", title: "Courses & batches" },
  batches: { active: "courses", crumb: "Academic setup", title: "Batch" },
  sessions: { active: "sessions", crumb: "Timetable", title: "Classes" },
  students: { active: "students", crumb: "People", title: "Students" },
  team: { active: "team", crumb: "People", title: "Staff" },
  attendance: { active: "attendance", crumb: "Oversight", title: "Attendance" },
  zoom: { active: "zoom", crumb: "Zoom", title: "Zoom review" },
  activity: { active: "activity", crumb: "Oversight", title: "Activity log" },
};

/** Shown the moment a staff link is clicked, in the right section, while the page's data loads. */
export default function StaffLoading() {
  const section = sections[usePathname().split("/")[2] ?? ""] ?? sections[""];
  return <StaffSkeleton {...section} subtitle="Loading…" />;
}
