import { Shell } from "@/components/Shell";

export default function Loading() {
  return (
    <Shell active="student">
      <div className="panel session-panel" style={{ maxWidth: "100%" }}>
        <div className="skeleton skeleton-title" style={{ width: "35%" }} />
        <div className="skeleton skeleton-line" style={{ width: "60%" }} />
        <div className="panels" style={{ marginTop: "24px" }}>
          <div className="panel skeleton skeleton-card" />
          <div className="panel skeleton skeleton-card" />
        </div>
      </div>
    </Shell>
  );
}
