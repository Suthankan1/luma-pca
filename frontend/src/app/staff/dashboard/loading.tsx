import { StaffSkeleton } from "@/components/StaffSkeleton";

export default function Loading() {
  return <StaffSkeleton active="dashboard" crumb="Attendance" title="Attendance dashboard" subtitle="Adding up attendance…" />;
}
