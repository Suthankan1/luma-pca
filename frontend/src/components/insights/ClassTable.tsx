import Link from "next/link";
import { formatClock, formatDay, formatDuration, level, stateLabels, type ClassSummary } from "@/lib/insights";
import { PercentBar } from "./Kpis";

export function ClassTable({ classes, minPercent }: { classes: ClassSummary[]; minPercent: number | null }) {
  return <div className="table-wrap">
    <table className="data-table" aria-label="Classes">
      <thead><tr>
        <th scope="col">Date</th><th scope="col">Class</th><th scope="col">Batch</th><th scope="col" className="num">Webinar length</th>
        <th scope="col" className="num">Joined</th><th scope="col" className="num">Average attendance</th><th scope="col">Status</th>
      </tr></thead>
      <tbody>
        {[...classes].reverse().map(c => <tr key={c.id}>
          <td data-label="Date">{formatDay(c.startsAt, c.timeZone)}<span className="muted"> · {formatClock(c.startsAt, c.timeZone)}</span></td>
          <td data-label="Class"><Link className="text-link" href={`/staff/sessions/${c.id}`}>{c.title}</Link></td>
          <td data-label="Batch">{c.batchCodes.join(", ")}</td>
          <td data-label="Webinar length" className="num">{formatDuration(c.classSeconds)}</td>
          <td data-label="Joined" className="num">{c.joined} / {c.expected}</td>
          <td data-label="Average attendance" className="num"><PercentBar percent={c.averagePercent} tone={level(c.averagePercent, minPercent, c.expected > 0)} /></td>
          <td data-label="Status"><span className={`state state-${c.state.toLowerCase()}`}>{stateLabels[c.state] ?? c.state}</span></td>
        </tr>)}
        {classes.length === 0 && <tr><td colSpan={7} className="muted empty-cell">No classes held in this period.</td></tr>}
      </tbody>
    </table>
  </div>;
}
