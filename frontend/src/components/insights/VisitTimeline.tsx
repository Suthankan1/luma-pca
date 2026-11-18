import { formatClock, formatDuration, gaps, timelineSegments, type Visit } from "@/lib/insights";

/**
 * Every connection of a student to one class: a bar over the class time (each joined stretch filled), then the list —
 * when they joined and left, for how long, and how long they were away before rejoining.
 */
export function VisitTimeline({ startsAt, endsAt, timeZone, visits }: { startsAt: string; endsAt: string; timeZone: string; visits: Visit[] }) {
  if (visits.length === 0) return <p className="muted timeline-empty">Not seen in the webinar.</p>;
  const sorted = [...visits].sort((a, b) => Date.parse(a.joinedAt) - Date.parse(b.joinedAt));
  const away = gaps(sorted);
  return <div className="timeline">
    <div className="timeline-bar" role="img" aria-label={`${sorted.length} connection${sorted.length === 1 ? "" : "s"} between ${formatClock(startsAt, timeZone)} and ${formatClock(endsAt, timeZone)}`}>
      {timelineSegments(startsAt, endsAt, sorted).map((s, i) =>
        <span key={i} className={`timeline-seg ${s.open ? "open" : ""}`} style={{ left: `${s.left}%`, width: `${s.width}%` }} />)}
    </div>
    <div className="timeline-scale" aria-hidden><span>{formatClock(startsAt, timeZone)}</span><span>{formatClock(endsAt, timeZone)}</span></div>
    <ol className="timeline-list" aria-label="Connections">
      {sorted.map((v, i) => <li key={`${v.joinedAt}-${i}`}>
        {i > 0 && away[i - 1] > 0 && <span className="timeline-away">Away {formatDuration(away[i - 1])}, then rejoined</span>}
        <span className="timeline-row">
          <strong>{i === 0 ? "Joined" : "Rejoined"} {formatClock(v.joinedAt, timeZone)}</strong>
          <span> → {v.open ? `still connected (or leave not received) ${formatClock(v.leftAt, timeZone)}` : `left ${formatClock(v.leftAt, timeZone)}`}</span>
          <span className="muted"> · {formatDuration(v.secondsInClass)} in class time</span>
        </span>
      </li>)}
    </ol>
  </div>;
}
