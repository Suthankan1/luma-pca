import { Shell } from "@/components/Shell";

export default function Loading() {
  return (
    <Shell active="student">
      <div className="panel session-panel" style={{ maxWidth: "100%" }}>
        <div className="skeleton skeleton-title" style={{ width: "40%" }} />
        <div className="skeleton skeleton-line" style={{ width: "65%" }} />
        <div className="panels" style={{ marginTop: "24px" }}>
          <div className="panel skeleton skeleton-card" />
        </div>
      </div>
    </Shell>
  );
}
