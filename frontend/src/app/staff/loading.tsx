"use client";

import { Suspense } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { StaffSkeleton } from "@/components/StaffSkeleton";
import { StaffShell, type StaffSection } from "@/components/StaffShell";
import { ClassesColumnSkeleton, StudentMonthSkeleton } from "@/components/staff/AttendanceSkeleton";

const sections: Record<string, { active: StaffSection; crumb: string; title: string }> = {
  "": { active: "overview", crumb: "", title: "Staff dashboard" },
  academy: { active: "academy", crumb: "Academy database", title: "Academy classes" },
  courses: { active: "courses", crumb: "Academic setup", title: "Courses & batches" },
  batches: { active: "courses", crumb: "Academic setup", title: "Batch" },
  sessions: { active: "sessions", crumb: "Teaching / Classes", title: "Classes" },
  "class-types": { active: "class-types", crumb: "Teaching / Class Types", title: "Class Types" },
  students: { active: "students", crumb: "People / Students", title: "Students" },
  reports: { active: "reports", crumb: "Teaching / Reports", title: "Reports" },
  attendance: { active: "attendance", crumb: "Teaching / Attendance", title: "Attendance" },
  zoom: { active: "zoom", crumb: "Zoom", title: "Zoom review" },
  activity: { active: "activity", crumb: "Oversight", title: "Activity log" },
};

/** Shown the moment a staff link is clicked, in the right section, while the page's data loads. */
export default function StaffLoading() {
  const [, , route, detail] = usePathname().split("/");
  const section = sections[route ?? ""] ?? sections[""];
  if (route === "sessions" && detail) {
    return <Suspense fallback={null}><ClassDetailLoading typeOverview={detail === "types"} /></Suspense>;
  }
  if (!detail && (route === "sessions" || route === "class-types" || route === "students")) {
    return <StaffShell {...section} subtitle="Loading…">
      <div className="classes-month-picker skeleton skeleton-line" style={{ width: 240 }} aria-hidden="true" />
      {route === "sessions" ? <div className="classes-overview"><ClassesColumnSkeleton /><ClassesColumnSkeleton /></div>
        : route === "class-types" ? <ClassesColumnSkeleton /> : <StudentMonthSkeleton />}
    </StaffShell>;
  }
  return <StaffSkeleton {...section} crumb={route === "students" ? "People / Students" : section.crumb}
    title={route === "students" ? (detail === "manage" ? "Manage students" : detail === "import" ? "Import students" : "Student") : section.title}
    subtitle="Loading…" />;
}

function ClassDetailLoading({ typeOverview }: { typeOverview: boolean }) {
  const fromClassTypes = useSearchParams().get("source") === "class-types";
  return <StaffSkeleton {...(fromClassTypes ? sections["class-types"] : sections.sessions)}
    title={typeOverview ? "Class type attendance" : "Class attendance"} subtitle="Loading class details…" />;
}
