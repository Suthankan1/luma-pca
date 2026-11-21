import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="attendance"
      crumb="Teaching / Attendance"
      title="Attendance"
      subtitle="Loading attendance..."
    />
  );
}
