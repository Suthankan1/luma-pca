import { Shell } from "@/components/Shell";

/** Shown the moment a student link is clicked, while the page's data loads. */
export default function StudentLoading() {
  return <Shell active="student">
    <h1 className="heading">Student portal</h1>
    <p className="sub" role="status">Loading…</p>
    <div className="panel">
      <div className="skeleton skeleton-title" style={{ width: "35%" }} />
      <div className="skeleton skeleton-line" />
      <div className="skeleton skeleton-line" style={{ width: "80%" }} />
    </div>
  </Shell>;
}
