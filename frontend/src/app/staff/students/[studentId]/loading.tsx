import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="students"
      crumb="Students"
      title="Student record"
      subtitle="Loading student record and enrollment history..."
    />
  );
}
