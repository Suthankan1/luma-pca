import { Shell } from "@/components/Shell";

export default function StudentPreview() {
  return <Shell active="student">
    <span className="eyebrow">STUDENT / PREVIEW ONLY</span><h1 className="heading">Your learning, clearly.</h1>
    <p className="sub">Eventually students will see their own schedules, verified attendance intervals, correction requests and optional participation activities.</p>
    <div className="banner"><b>Preview only.</b> Login is not implemented. This page does not access real student records or store identifiable reactions.</div>
    <div className="two">
      <div className="callout mt-0"><span className="chip cyan">FICTIONAL DEMO</span><h2 className="text-3xl font-bold mt-8">94.2%</h2><p className="mt-2 text-cyan-100">Illustrative monthly connection attendance — not a measurement of learning or attention.</p></div>
      <div className="panel"><h2 className="font-bold text-xl mb-3">Privacy comes first</h2><div className="listrow"><span>Own attendance</span><span>Planned</span></div><div className="listrow"><span>Correction requests</span><span>Planned</span></div><div className="listrow"><span>Academy-owned reactions</span><span>Optional</span></div><div className="listrow"><span>Native Zoom emoji counts</span><span>Unverified</span></div></div>
    </div>
  </Shell>;
}
