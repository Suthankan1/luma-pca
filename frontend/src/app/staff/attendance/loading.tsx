import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="attendance"
      crumb="Operations"
      title="Attendance"
      subtitle="Loading attendance..."
    />
  );
}
