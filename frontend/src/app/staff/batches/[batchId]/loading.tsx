import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="courses"
      crumb="Academic setup"
      title="Batch roster"
      subtitle="Loading batch roster and attendance summary..."
    />
  );
}
