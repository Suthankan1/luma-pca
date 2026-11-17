import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="overview"
      crumb="Operations"
      title="Overview"
      subtitle="Loading academy overview..."
    />
  );
}
