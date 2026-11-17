import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="students"
      crumb="People"
      title="Students"
      subtitle="Loading students..."
    />
  );
}
