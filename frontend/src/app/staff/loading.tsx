"use client";

import { usePathname } from "next/navigation";
import { StaffSkeleton } from "@/components/StaffSkeleton";
import { StaffShell, type StaffSection } from "@/components/StaffShell";
import { ClassesColumnSkeleton, StudentMonthSkeleton } from "@/components/staff/AttendanceSkeleton";

const sections: Record<string, { active: StaffSection; crumb: string; title: string }> = {
  "": { active: "overview", crumb: "Operations", title: "Staff portal" },
  dashboard: { active: "dashboard", crumb: "Attendance", title: "Attendance reports" },
  academy: { active: "academy", crumb: "Academy database", title: "Academy classes" },
  courses: { active: "courses", crumb: "Academic setup", title: "Courses & batches" },
  batches: { active: "courses", crumb: "Academic setup", title: "Batch" },
  sessions: { active: "sessions", crumb: "Teaching", title: "Classes" },
  "class-types": { active: "class-types", crumb: "Teaching", title: "Class Types" },
  students: { active: "students", crumb: "People", title: "Students" },
  reports: { active: "reports", crumb: "Teaching", title: "Reports" },
  attendance: { active: "attendance", crumb: "Oversight", title: "Attendance" },
  zoom: { active: "zoom", crumb: "Zoom", title: "Zoom review" },
  activity: { active: "activity", crumb: "Oversight", title: "Activity log" },
};

/** Shown the moment a staff link is clicked, in the right section, while the page's data loads. */
export default function StaffLoading() {
  const [, , route, detail] = usePathname().split("/");
  const section = sections[route ?? ""] ?? sections[""];
  if (!detail && (route === "sessions" || route === "class-types" || route === "students")) {
    return <StaffShell {...section} subtitle="Loading…">
      <div className="classes-month-picker skeleton skeleton-line" style={{ width: 240 }} aria-hidden="true" />
      {route === "sessions" ? <div className="classes-overview"><ClassesColumnSkeleton /><ClassesColumnSkeleton /></div>
        : route === "class-types" ? <ClassesColumnSkeleton /> : <StudentMonthSkeleton />}
    </StaffShell>;
  }
  return <StaffSkeleton {...section} subtitle="Loading…" />;
}
