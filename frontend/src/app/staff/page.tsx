import { Shell } from "@/components/Shell";

export default function StaffPreview() {
  return <Shell active="staff">
    <span className="eyebrow">STAFF / PREVIEW ONLY</span>
    <h1 className="heading">Academy operations.</h1>
    <p className="sub">A responsive visual shell for the administration experience. The eventual production version requires staff JWT login and course/batch scoping.</p>
    <div className="banner"><b>Fictional sample content.</b> All values below are illustrative. No authenticated account, API data, Zoom event or roster has been loaded.</div>
    <div className="panels">
      <div className="panel"><span className="chip cyan">SAMPLE</span><div className="value">03</div><p className="muted">Scheduled sessions</p></div>
      <div className="panel"><span className="chip cyan">SAMPLE</span><div className="value">84</div><p className="muted">Students connected</p></div>
      <div className="panel"><span className="chip cyan">SAMPLE</span><div className="value">12</div><p className="muted">Records requiring review</p></div>
    </div>
    <div className="two">
      <div className="panel"><h2 className="font-bold text-xl mb-3">Today&apos;s sample schedule</h2>
        <div className="listrow"><span>09:00 · Physics</span><span className="muted">Completed</span></div>
        <div className="listrow"><span>14:00 · Advanced Mathematics</span><span className="muted">Live (fictional)</span></div>
        <div className="listrow"><span>18:00 · Chemistry</span><span className="muted">Upcoming</span></div>
      </div>
      <div className="callout mt-0"><span className="chip cyan">BACKEND FOUNDATION</span><h2 className="text-2xl font-bold mt-6">Policy + evidence.</h2><p className="mt-4 text-cyan-100">Attendance will be derived from verified connection intervals and signed reports, never from guessed attention.</p></div>
    </div>
  </Shell>;
}
