"use client";

import Link from "next/link";
import { useState, type CSSProperties } from "react";
import { shortDayLabel, weekdayIndex, type ClassBar } from "@/lib/staff-home";

/**
 * One column per class held in the month: the share of expected students who joined, with the month's average as a line.
 * Hovering or focusing a column shows its class in the readout above the chart (each column's link also carries it as its
 * name); "Show as table" lists every value without hovering.
 */
export function AttendanceBars({ bars, average }: { bars: ClassBar[]; average: number }) {
  const [active, setActive] = useState<ClassBar | null>(null);
  const dense = bars.length > 16;

  return <figure className="bars">
    <div className="bars-readout" aria-hidden="true">
      {active ? <>
        <strong>{active.percent}%</strong>
        <span className="bars-readout-copy">
          <span className="bars-readout-title">{active.title}</span>
          <span>{shortDayLabel(active.day)} · {active.joined} of {active.expected} joined</span>
        </span>
      </> : <>
        <strong>{average}%</strong>
        <span className="bars-readout-copy"><span>Average attendance across {bars.length} class{bars.length === 1 ? "" : "es"}</span>
          <span>Each column is one class</span></span>
      </>}
    </div>
    <div className="bars-scroll">
      <div className="bars-plot" style={{ "--count": bars.length } as CSSProperties}>
        {[0, 50, 100].map(at => <span key={at} className="bars-grid" style={{ "--at": at } as CSSProperties} aria-hidden="true">
          <span>{at}%</span>
        </span>)}
        <span className="bars-average" style={{ "--at": average } as CSSProperties} aria-hidden="true"><span>Avg {average}%</span></span>
        <ol className="bars-list">
          {bars.map((bar, i) => {
            const newDay = i === 0 || bars[i - 1].day !== bar.day;
            const label = newDay && (!dense || i === 0 || weekdayIndex(bar.day) === 0) ? String(Number(bar.day.slice(8))) : "";
            return <li key={bar.id} className="bars-col">
              <Link href={`/staff/sessions/${bar.id}`} className="bars-bar" style={{ "--v": bar.percent } as CSSProperties}
                aria-label={`${bar.title}, ${shortDayLabel(bar.day)}: ${bar.percent}%, ${bar.joined} of ${bar.expected} joined`}
                onPointerEnter={() => setActive(bar)} onPointerLeave={() => setActive(null)}
                onFocus={() => setActive(bar)} onBlur={() => setActive(null)}>
                <span className="bars-fill" />
              </Link>
              <span className="bars-day" aria-hidden="true">{label}</span>
            </li>;
          })}
        </ol>
      </div>
    </div>
    <details className="bars-table">
      <summary>Show as table</summary>
      <table>
        <thead><tr><th scope="col">Date</th><th scope="col">Class</th><th scope="col">Joined</th><th scope="col">Attendance</th></tr></thead>
        <tbody>
          {bars.map(bar => <tr key={bar.id}>
            <td>{shortDayLabel(bar.day)}</td>
            <td><Link className="text-link" href={`/staff/sessions/${bar.id}`}>{bar.title}</Link></td>
            <td>{bar.joined} of {bar.expected}</td>
            <td>{bar.percent}%</td>
          </tr>)}
        </tbody>
      </table>
    </details>
  </figure>;
}
