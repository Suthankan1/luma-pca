import { StaffShell, type StaffSection } from "./StaffShell";

export function StaffSkeleton({
  active,
  crumb,
  title,
  subtitle,
}: {
  active: StaffSection;
  crumb: string;
  title: string;
  subtitle: string;
}) {
  return (
    <StaffShell active={active} crumb={crumb} title={title} subtitle={subtitle}>
      <div className="skeleton skeleton-title" style={{ width: "30%" }} />
      <div className="skeleton skeleton-line" style={{ width: "60%" }} />
      <div className="panels" style={{ marginTop: "24px" }}>
        <div className="panel skeleton skeleton-card" />
        <div className="panel skeleton skeleton-card" />
        <div className="panel skeleton skeleton-card" />
      </div>
      <div className="panel panel-gap">
        <div className="skeleton skeleton-title" style={{ width: "25%" }} />
        <div className="skeleton skeleton-line" />
        <div className="skeleton skeleton-line" style={{ width: "80%" }} />
        <div className="skeleton skeleton-line" style={{ width: "90%" }} />
      </div>
    </StaffShell>
  );
}
