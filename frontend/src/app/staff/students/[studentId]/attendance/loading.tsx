import { StaffShell } from "@/components/StaffShell";
import { StudentProfileSkeleton } from "@/components/StudentAttendanceProfile";

export default function LoadingStudentAttendance() {
  return <StaffShell active="students" crumb="Students / Attendance" title="Student attendance" subtitle="Loading profile…">
    <div className="classes-month-picker skeleton skeleton-line" style={{ width: 240 }} aria-hidden="true" />
    <StudentProfileSkeleton />
  </StaffShell>;
}
