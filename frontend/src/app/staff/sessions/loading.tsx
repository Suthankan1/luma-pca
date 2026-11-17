import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="sessions"
      crumb="Timetable"
      title="Classes"
      subtitle="Loading classes..."
    />
  );
}
