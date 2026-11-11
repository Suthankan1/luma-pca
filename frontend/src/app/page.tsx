import Link from "next/link";
import { Shell } from "@/components/Shell";

export default function Home() {
  return <Shell>
    <span className="eyebrow">WELCOME TO PHYSICS CUBE ACADEMY</span>
    <h1 className="heading">Your classroom,<br/>in focus.</h1>
    <p className="sub">LUMA is being built to connect Zoom Webinar attendance with course and batch records, student engagement and evidence-led academy decisions.</p>
    <div className="banner"><strong>Architecture bootstrap only.</strong> This screen is not an active attendance dashboard. Student/staff JWT login, batch authorization, live Zoom ingestion and production reports are planned implementation slices.</div>
    <div className="panels">
      <div className="panel"><span className="chip">DAY-ONE DOMAIN</span><div className="value">Courses</div><p className="sub">Multiple courses, batches, enrollments and shared sessions are modeled as fundamental entities.</p></div>
      <div className="panel"><span className="chip">SECURITY GATE</span><div className="value">JWT</div><p className="sub">First-party authentication, refresh rotation, server-side scopes and secure browser cookies are specified.</p></div>
      <div className="panel"><span className="chip">ZOOM EVIDENCE</span><div className="value">Reconcile</div><p className="sub">Live join/leave data is provisional until official report reconciliation.</p></div>
    </div>
    <div className="nav-links"><Link className="action" href="/staff">Explore staff preview →</Link><Link className="action secondary" href="/student">Explore student preview →</Link></div>
  </Shell>;
}
