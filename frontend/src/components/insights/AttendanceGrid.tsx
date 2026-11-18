import Link from "next/link";
import { formatDuration, formatShortDay, level, outcomeLabels, type ClassSummary, type StudentSummary } from "@/lib/insights";

/** Students down, classes across: each cell is the share of that class the student was connected for. */
export function AttendanceGrid({ classes, students, minPercent, studentHref }: {
  classes: ClassSummary[]; students: StudentSummary[]; minPercent: number | null; studentHref: Record<string, string> | null;
}) {
  if (classes.length === 0) return <p className="muted">No classes held in this period.</p>;
  return <div className="grid-wrap">
    <table className="att-grid" aria-label="Attendance grid">
      <thead><tr>
        <th scope="col" className="grid-student">Student</th>
        {classes.map(c => <th key={c.id} scope="col" className="grid-class">
          <Link href={`/staff/sessions/${c.id}`} title={c.title}>{formatShortDay(c.startsAt, c.timeZone)}</Link>
        </th>)}
        <th scope="col" className="num">Total</th>
      </tr></thead>
      <tbody>{students.map(s => <tr key={s.studentId}>
        <th scope="row" className="grid-student">
          {studentHref?.[s.studentId] ? <Link className="text-link" href={studentHref[s.studentId]}>{s.studentNumber}</Link> : s.studentNumber}
          <span className="muted grid-name"> {s.fullName}</span>
        </th>
        {s.cells.map((cell, i) => {
          if (!cell) return <td key={i} className="grid-cell grid-na" title="Not in this class"><span className="sr-only">not expected</span></td>;
          const tone = level(cell.percent, minPercent, cell.visits > 0);
          const tip = `${classes[i].title}: ${formatDuration(cell.connectedSeconds)} of ${formatDuration(classes[i].classSeconds)}`
            + `, ${cell.visits} connection${cell.visits === 1 ? "" : "s"}${cell.outcome ? `, ${outcomeLabels[cell.outcome] ?? cell.outcome}` : ""}`;
          return <td key={i} className={`grid-cell grid-${tone}`} title={tip}>
            <span className="sr-only">{tip}</span><span aria-hidden>{cell.visits > 0 ? cell.percent : "–"}</span>
            {cell.connectedNow && <span className="grid-live" aria-hidden>●</span>}
          </td>;
        })}
        <td className="num"><strong>{s.percent}%</strong></td>
      </tr>)}</tbody>
    </table>
    <p className="grid-legend muted"><span className="grid-key grid-good" /> at or above the rule <span className="grid-key grid-warn" /> close
      <span className="grid-key grid-bad" /> well below <span className="grid-key grid-none" /> not seen · numbers are % of the class</p>
  </div>;
}
