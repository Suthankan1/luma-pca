import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="sessions"
      crumb="Timetable"
      title="Class details"
      subtitle="Loading class details and attendance..."
    />
  );
}
