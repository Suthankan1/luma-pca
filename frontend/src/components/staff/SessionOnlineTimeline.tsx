"use client";

import { useId, useState, type PointerEvent } from "react";
import type { OnlineObservation } from "@/lib/staff-forms";

export function SessionOnlineTimeline({ history, start, end, endLabel, timeZone }: {
  history: OnlineObservation[];
  start: number;
  end: number;
  endLabel: string;
  timeZone: string;
}) {
  const titleId = useId();
  const sliderId = useId();
  const [selected, setSelected] = useState<number | null>(null);
  const samples = history.map(sample => ({ at: Date.parse(sample.at), online: sample.online }));
  const last = samples.at(-1);
  const index = Math.min(selected ?? samples.length - 1, samples.length - 1);
  const active = samples[index];
  const left = Math.min(start, samples[0]?.at ?? start);
  const right = Math.max(end, last?.at ?? end);
  const span = Math.max(1, right - left);
  const peak = samples.reduce((max, sample) => Math.max(max, sample.online), 0);
  const ceiling = Math.max(2, Math.ceil(peak / 2) * 2);
  const x = (at: number) => (at - left) * 100 / span;
  const y = (online: number) => (ceiling - online) * 100 / ceiling;
  const line = samples.map(({ at, online }, i) => i === 0 ? `M${x(at)},${y(online)}`
    : `H${x(at)}V${y(online)}`).join(" ");
  const clock = new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", second: "2-digit" });
  const sampleLabel = active && `${clock.format(active.at)} · ${active.online} online`;

  function inspect(event: PointerEvent<SVGSVGElement>) {
    const { left: offset, width } = event.currentTarget.getBoundingClientRect();
    const at = left + (event.clientX - offset) / width * span;
    const after = samples.findIndex(sample => sample.at >= at);
    setSelected(after < 0 ? samples.length - 1
      : after > 0 && at - samples[after - 1].at < samples[after].at - at ? after - 1 : after);
  }

  return <figure className="panel panel-gap session-timeline" aria-labelledby={titleId}>
    <figcaption id={titleId} className="panel-title">Students online over time</figcaption>
    {last && active ? <>
      <p className="panel-sub">{samples.length} {samples.length === 1 ? "sample" : "samples"} from Zoom join and leave data, at 30-second intervals.</p>
      <div className="session-timeline-readout"><strong>{sampleLabel}</strong><span>Peak {peak}</span></div>
      <div className="session-timeline-plot">
        <div className="session-timeline-scale" aria-hidden="true"><span>{ceiling}</span><span>{ceiling / 2}</span><span>0</span></div>
        <div className="session-timeline-canvas">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true" onPointerMove={inspect} onPointerDown={inspect}>
            <path className="class-trend-grid" d="M0 0H100 M0 50H100 M0 100H100" vectorEffect="non-scaling-stroke" />
            <path className="session-timeline-line" d={line} vectorEffect="non-scaling-stroke" />
            <path className="session-timeline-cursor" d={`M${x(active.at)} 0V100`} vectorEffect="non-scaling-stroke" />
          </svg>
          <span className="session-timeline-dot" aria-hidden="true" style={{ left: `${x(active.at)}%`, top: `${y(active.online)}%` }} />
        </div>
      </div>
      <div className="session-timeline-times"><span>Start {clock.format(left)}</span><span>{endLabel} {clock.format(right)}</span></div>
      {samples.length > 1 && <div className="session-timeline-scrub">
        <label htmlFor={sliderId}>Explore timeline</label>
        <input id={sliderId} type="range" min={0} max={samples.length - 1} value={index}
          aria-valuetext={sampleLabel} onChange={event => setSelected(event.currentTarget.valueAsNumber)} />
      </div>}
    </> : <p className="panel-sub">No recorded online samples are available for this session.</p>}
  </figure>;
}
