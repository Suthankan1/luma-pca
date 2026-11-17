import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return (
    <StaffSkeleton
      active="team"
      crumb="Staff"
      title="Staff account"
      subtitle="Loading staff account details..."
    />
  );
}
