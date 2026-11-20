import type { CSSProperties } from "react";
import { PanelRetry } from "./PanelRetry";

type Bar = { id: string; label: string; title: string; percent: number | null };

export function AttendanceChart({ title, bars, failed }: { title: string; bars: Bar[]; failed: boolean }) {
  return <figure className="panel classes-attendance-chart">
    <figcaption className="panel-title">{title}</figcaption>
    {failed ? <p className="session-error" role="alert">Attendance couldn’t load. <PanelRetry /></p>
      : bars.length === 0 ? <p className="panel-sub">No attendance percentages in this period yet.</p>
        : <div className="classes-chart-frame">
        <div className="classes-chart-axis" aria-hidden="true"><span>100%</span><span>50%</span><span>0%</span></div>
        <ul className="classes-chart-bars" aria-label={title}>
          {bars.map(({ id, label, title, percent }) => <li key={id} tabIndex={0}
            title={`${title} · ${percent == null ? "Attendance unavailable" : `${percent}%`}`}
            aria-label={`${title}, ${percent == null ? "attendance unavailable" : `${percent}%`}`}>
            <div className="classes-chart-track" aria-hidden="true">
              {percent != null && <div className="classes-chart-fill" style={{ "--attendance": `${percent}%` } as CSSProperties} />}
              <span className="classes-chart-value">{percent == null ? "—" : `${percent}%`}</span>
            </div>
            <span className="classes-chart-label" aria-hidden="true">{label}</span>
          </li>)}
        </ul>
      </div>}
  </figure>;
}
