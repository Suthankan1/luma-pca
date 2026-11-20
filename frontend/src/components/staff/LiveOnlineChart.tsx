"use client";

import { useId, useState } from "react";
import { ACADEMY_TIME_ZONE } from "@/lib/staff-forms";

type Observation = { at: number; online: number };
type Series = { sessionId: string; observations: Observation[]; animateAt: number | null };

const MAX_OBSERVATIONS = 1441; // Twelve hours at the 30-second refresh interval, including the first count.
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

/** Chart the counts observed by this tab as the live page refreshes. */
export function LiveOnlineChart({ sessionId, connectedNow, sampledAt, startsAt, endsAt }: {
  sessionId: string;
  connectedNow: number | null;
  sampledAt: number;
  startsAt: string;
  endsAt: string | null;
}) {
  const labelId = useId();
  const incoming = connectedNow === null ? [] : [{ at: sampledAt, online: connectedNow }];
  const [series, setSeries] = useState<Series>(() => ({
    sessionId, observations: incoming, animateAt: null,
  }));
  const { observations, animateAt } = series;
  const latest = observations.at(-1);
  const sameClass = series.sessionId === sessionId;
  const hasNewCount = connectedNow !== null && (!latest || sampledAt > latest.at
    || (sampledAt === latest.at && connectedNow !== latest.online));

  if (!sameClass || hasNewCount) {
    const previous = sameClass && latest?.at === sampledAt ? observations.slice(0, -1) : observations;
    setSeries({ sessionId, observations: [...(sameClass ? previous : []), ...incoming].slice(-MAX_OBSERVATIONS),
      animateAt: sameClass && (!latest || latest.at < sampledAt) ? sampledAt : null });
  }

  return <figure className="live-online-chart w-full min-w-0 max-w-[320px] text-[var(--ink-3)]" aria-labelledby={labelId}>
    <figcaption id={labelId} className="text-xs font-semibold text-[var(--ink-2)]">Online over time</figcaption>
    {latest ? <OnlinePlot observations={observations} animateAt={animateAt} startsAt={startsAt} endsAt={endsAt} />
      : <p className="text-xs">No online counts available yet.</p>}
    {latest && connectedNow === null && <p className="mt-1 text-xs">Current count unavailable. Showing the last observations.</p>}
  </figure>;
}

function OnlinePlot({ observations, animateAt, startsAt, endsAt }: {
  observations: Observation[];
  animateAt: number | null;
  startsAt: string;
  endsAt: string | null;
}) {
  const scaleId = useId();
  const first = observations[0];
  const latest = observations[observations.length - 1];
  const firstTime = TIME.format(first.at);
  const lastTime = TIME.format(latest.at);
  const counts = observations.map(point => point.online);
  const min = Math.min(...counts);
  const max = Math.max(...counts);
  const padding = Math.max(2, Math.ceil((max - min) * 0.12));
  const floor = Math.max(0, min - padding);
  const ceiling = Math.max(max + padding, floor + 4);

  const started = Date.parse(startsAt);
  const start = Number.isFinite(started) ? started : first.at;
  const scheduledEnd = endsAt ? Date.parse(endsAt) : NaN;
  const hasScheduledEnd = scheduledEnd > start && scheduledEnd >= latest.at;
  const end = hasScheduledEnd ? scheduledEnd : latest.at;
  const duration = Math.max(1, end - start);
  const axisClock = end - start < 60_000 ? TIME : AXIS_TIME;
  const endLabel = hasScheduledEnd ? "Scheduled end" : "Latest";
  const endTime = (hasScheduledEnd ? axisClock : TIME).format(end);
  const points = observations.map(point => ({
    at: point.at,
    x: (PLOT.left + (PLOT.right - PLOT.left) * Math.max(0, point.at - start) / duration).toFixed(2),
    y: (PLOT.bottom - (PLOT.bottom - PLOT.top) * (point.online - floor) / (ceiling - floor)).toFixed(2),
  }));
  const animateSegment = points.length > 1 && animateAt === latest.at;
  const history = animateSegment ? points.slice(0, -1) : points;
  const coordinates = (items: typeof points) => items.map(({ x, y }) => `${x},${y}`).join(" ");
  const markers = points.length <= MAX_MARKERS ? points : [points[0], ...points.slice(-(MAX_MARKERS - 1))];
  const description = points.length === 1 ? `1 observation · ${latest.online} online at ${lastTime}`
    : `${points.length} observations · ${first.online} online at ${firstTime} · ${latest.online} online at ${lastTime}`;

  return <>
    <svg className="block h-auto w-full" viewBox="0 0 320 56" role="img" aria-label={description} aria-describedby={scaleId}>
      <desc id={scaleId}>Online count scale from {floor} to {ceiling}. Class start at {axisClock.format(start)}. {endLabel} at {endTime}.</desc>
      <path d={`M${PLOT.left} ${PLOT.top}H${PLOT.right} M${PLOT.left} ${PLOT.bottom}H${PLOT.right}`} stroke="#d6e4ec" fill="none" />
      <text x="23" y="13" textAnchor="end" className="fill-current text-[10px]">{ceiling}</text>
      <text x="23" y="53" textAnchor="end" className="fill-current text-[10px]">{floor}</text>
      {history.length > 1 && <polyline points={coordinates(history)} {...LINE} />}
      {animateSegment && <polyline key={latest.at} points={coordinates(points.slice(-2))} pathLength={1}
        className="live-online-segment" {...LINE} />}
      {markers.map(({ at, x, y }) => <circle key={at} cx={x} cy={y} r={at === latest.at ? 3.5 : 2}
        className={at === animateAt ? "live-online-new-point" : undefined} fill="#116d70" />)}
    </svg>
    <div className="flex justify-between gap-2 pl-[9%] pr-[3%] text-[11px] tabular-nums" aria-hidden="true">
      <span>Start <time dateTime={new Date(start).toISOString()}>{axisClock.format(start)}</time></span>
      <span>{endLabel} <time dateTime={new Date(end).toISOString()}>{endTime}</time></span>
    </div>
  </>;
}
