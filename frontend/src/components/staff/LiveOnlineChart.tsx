"use client";

import { useId, useState } from "react";
import { ACADEMY_TIME_ZONE } from "@/lib/staff-forms";

type Observation = { at: number; online: number };
type OnlineHistory = readonly { at: string; online: number }[];
type Series = { sessionId: string; response: OnlineHistory | null; observations: Observation[]; animateAt: number | null };

const MAX_MARKERS = 24;
const TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: ACADEMY_TIME_ZONE, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});
const AXIS_TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: ACADEMY_TIME_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});
const PLOT = { left: 29, right: 310, top: 10, bottom: 50 };
const LINE = { fill: "none", stroke: "#116d70", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round",
  vectorEffect: "non-scaling-stroke" } as const;

function readObservations(history: OnlineHistory | null): Observation[] {
  const countsByTime = new Map<number, number>();
  for (const observation of history ?? []) {
    const at = Date.parse(observation.at);
    if (Number.isFinite(at) && Number.isInteger(observation.online) && observation.online >= 0) {
      countsByTime.set(at, observation.online);
    }
  }
  return [...countsByTime].sort(([a], [b]) => a - b).map(([at, online]) => ({ at, online }));
}

/** Chart the stored online history for a live class. */
export function LiveOnlineChart({ sessionId, history, sampledAt, startsAt, wide = false }: {
  sessionId: string;
  history: OnlineHistory | null;
  sampledAt: number;
  startsAt: string;
  wide?: boolean;
}) {
  const labelId = useId();
  const [series, setSeries] = useState<Series>(() => ({
    sessionId, response: history, observations: readObservations(history), animateAt: null,
  }));
  const { observations, animateAt } = series;
  const latest = observations.at(-1);
  const sameClass = series.sessionId === sessionId;

  if (!sameClass || history !== series.response) {
    const received = history === null && sameClass ? observations : readObservations(history);
    const nextLatest = received.at(-1);
    const animateNext = sameClass && latest && nextLatest && nextLatest.at > latest.at;
    setSeries({ sessionId, response: history, observations: received, animateAt: animateNext ? nextLatest.at : null });
  }

  return <figure className={`live-online-chart w-full min-w-0 ${wide ? "" : "max-w-[320px]"} text-[var(--ink-3)]`} aria-labelledby={labelId}>
    <figcaption id={labelId} className="text-xs font-semibold text-[var(--ink-2)]">Students online over time</figcaption>
    {latest ? <OnlinePlot observations={observations} animateAt={animateAt} startsAt={startsAt} sampledAt={sampledAt} interactive={wide} />
      : <p className="text-xs">No online counts available yet.</p>}
    {latest && history === null && <p className="mt-1 text-xs">Online history unavailable. Showing the last observations.</p>}
  </figure>;
}

function OnlinePlot({ observations, animateAt, startsAt, sampledAt, interactive }: {
  observations: Observation[];
  animateAt: number | null;
  startsAt: string;
  sampledAt: number;
  interactive: boolean;
}) {
  const scaleId = useId();
  const [selectedAt, setSelectedAt] = useState<number | null>(null);
  const first = observations[0];
  const latest = observations[observations.length - 1];
  const selection = observations.findIndex(point => point.at === selectedAt);
  const selectedIndex = selection < 0 ? observations.length - 1 : selection;
  const selected = observations[selectedIndex];
  const firstTime = TIME.format(first.at);
  const lastTime = TIME.format(latest.at);
  let min = first.online;
  let max = first.online;
  for (const observation of observations) {
    min = Math.min(min, observation.online);
    max = Math.max(max, observation.online);
  }
  const padding = Math.max(2, Math.ceil((max - min) * 0.12));
  const floor = Math.max(0, min - padding);
  const ceiling = Math.max(max + padding, floor + 4);

  const started = Date.parse(startsAt);
  const start = Number.isFinite(started) ? started : first.at;
  const end = Math.max(sampledAt, latest.at);
  const duration = Math.max(1, end - start);
  const axisClock = end - start < 60_000 ? TIME : AXIS_TIME;
  const endTime = TIME.format(end);
  const points = observations.map(point => ({
    at: point.at,
    online: point.online,
    x: (PLOT.left + (PLOT.right - PLOT.left) * Math.max(0, point.at - start) / duration).toFixed(2),
    y: (PLOT.bottom - (PLOT.bottom - PLOT.top) * (point.online - floor) / (ceiling - floor)).toFixed(2),
  }));
  const animateSegment = points.length > 1 && animateAt === latest.at;
  const history = animateSegment ? points.slice(0, -1) : points;
  const coordinates = (items: typeof points) => items.map(({ x, y }) => `${x},${y}`).join(" ");
  const markers = points.length <= MAX_MARKERS ? points : [points[0], ...points.slice(-(MAX_MARKERS - 1))];
  const focusPoint = points[selectedIndex];
  const description = points.length === 1 ? `1 observation · ${latest.online} online at ${lastTime}`
    : `${points.length} observations · ${first.online} online at ${firstTime} · ${latest.online} online at ${lastTime}`;

  return <>
    <svg className="block h-auto w-full" viewBox="0 0 320 56" role="img" aria-label={description} aria-describedby={scaleId}>
      <desc id={scaleId}>Online count scale from {floor} to {ceiling}. Class start at {axisClock.format(start)}. Current time {endTime}.</desc>
      <path d={`M${PLOT.left} ${PLOT.top}H${PLOT.right} M${PLOT.left} ${PLOT.bottom}H${PLOT.right}`} stroke="#d6e4ec" fill="none" />
      <text x="23" y="13" textAnchor="end" className="fill-current text-[10px]">{ceiling}</text>
      <text x="23" y="53" textAnchor="end" className="fill-current text-[10px]">{floor}</text>
      {history.length > 1 && <polyline points={coordinates(history)} {...LINE} />}
      {animateSegment && <polyline key={latest.at} points={coordinates(points.slice(-2))} pathLength={1}
        className="live-online-segment" {...LINE} />}
      {markers.map(({ at, x, y, online }) => <circle key={at} cx={x} cy={y} r={at === latest.at ? 3.5 : 2}
        className={at === animateAt ? "live-online-new-point" : undefined} fill="#116d70">
        <title>{TIME.format(at)} · {online} online</title>
      </circle>)}
      {interactive && <circle cx={focusPoint.x} cy={focusPoint.y} r="5" fill="none" stroke="#116d70" />}
    </svg>
    <div className="flex justify-between gap-2 pl-[9%] pr-[3%] text-[11px] tabular-nums" aria-hidden="true">
      <span>Start <time dateTime={new Date(start).toISOString()}>{axisClock.format(start)}</time></span>
      <span>Now <time dateTime={new Date(end).toISOString()}>{endTime}</time></span>
    </div>
    {interactive && observations.length > 1 && <div className="student-live-chart-control">
      <input type="range" min={0} max={observations.length - 1} value={selectedIndex} aria-label="Explore online counts over time"
        aria-valuetext={`${TIME.format(selected.at)}, ${selected.online} online`}
        onChange={event => {
          const index = Number(event.currentTarget.value);
          setSelectedAt(index === observations.length - 1 ? null : observations[index].at);
        }} />
      <output>{TIME.format(selected.at)} · {selected.online} online</output>
    </div>}
  </>;
}
