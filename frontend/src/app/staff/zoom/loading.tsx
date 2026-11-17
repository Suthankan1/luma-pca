import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="zoom"
      crumb="Zoom"
      title="Zoom review"
      subtitle="Loading Zoom occurrences and receipts..."
    />
  );
}
