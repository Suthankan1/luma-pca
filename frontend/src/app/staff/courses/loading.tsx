import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="courses"
      crumb="Academic setup"
      title="Courses & batches"
      subtitle="Loading courses and batches..."
    />
  );
}
