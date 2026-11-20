"use client";

import { useId, useState } from "react";
import { ACADEMY_TIME_ZONE } from "@/lib/staff-forms";

type Observation = { at: number; online: number };

const HISTORY_MS = 30 * 60_000;
const MAX_OBSERVATIONS = 61;
const TIME = new Intl.DateTimeFormat("en-GB", {
  timeZone: ACADEMY_TIME_ZONE, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});
const PLOT = { left: 29, right: 310, top: 10, bottom: 50 };

/** Chart the counts observed by this tab as the live page refreshes. */
export function LiveOnlineChart({ sessionId, connectedNow, sampledAt }: {
  sessionId: string;
  connectedNow: number | null;
  sampledAt: number;
}) {
  const labelId = useId();
  const incoming = connectedNow === null ? [] : [{ at: sampledAt, online: connectedNow }];
  const [series, setSeries] = useState<{ sessionId: string; observations: Observation[] }>(() => ({
    sessionId, observations: incoming,
  }));
  const { observations } = series;
  const latest = observations.at(-1);
  const sameClass = series.sessionId === sessionId;
  const hasNewCount = connectedNow !== null && (!latest || sampledAt > latest.at
    || (sampledAt === latest.at && connectedNow !== latest.online));

  if (!sameClass || hasNewCount) {
    const previous = sameClass
      ? observations.filter(point => point.at >= sampledAt - HISTORY_MS && point.at < sampledAt)
      : [];
    setSeries({ sessionId, observations: [...previous, ...incoming].slice(-MAX_OBSERVATIONS) });
  }

  return <figure className="live-online-chart w-full min-w-0 max-w-[320px] text-[var(--ink-3)]" aria-labelledby={labelId}>
    <figcaption id={labelId} className="text-xs font-semibold text-[var(--ink-2)]">Online over time</figcaption>
    {latest ? <OnlinePlot observations={observations} /> : <p className="text-xs">No online counts available yet.</p>}
    {latest && connectedNow === null && <p className="mt-1 text-xs">Current count unavailable. Showing the last observations.</p>}
  </figure>;
}

function OnlinePlot({ observations }: { observations: Observation[] }) {
  const first = observations[0];
  const latest = observations[observations.length - 1];
  const single = observations.length === 1;
  const firstTime = TIME.format(first.at);
  const lastTime = TIME.format(latest.at);
  const ceiling = Math.max(1, ...observations.map(point => point.online));
  const points = observations.map(point => ({
    at: point.at,
    x: single ? (PLOT.left + PLOT.right) / 2 : PLOT.left + (PLOT.right - PLOT.left) * (point.at - first.at) / (latest.at - first.at),
    y: PLOT.bottom - (PLOT.bottom - PLOT.top) * point.online / ceiling,
  }));
  const description = single ? `1 observation · ${latest.online} online at ${lastTime}`
    : `${points.length} observations · ${first.online} online at ${firstTime} · ${latest.online} online at ${lastTime}`;

  return <>
    <svg className="block h-auto w-full" viewBox="0 0 320 56" role="img" aria-label={description}>
      <path d={`M${PLOT.left} ${PLOT.top}H${PLOT.right} M${PLOT.left} ${PLOT.bottom}H${PLOT.right}`} stroke="#d6e4ec" fill="none" />
      <text x="23" y="13" textAnchor="end" className="fill-current text-[10px]">{ceiling}</text>
      <text x="23" y="53" textAnchor="end" className="fill-current text-[10px]">0</text>
      {!single && <polyline points={points.map(({ x, y }) => `${x},${y}`).join(" ")} fill="none"
        stroke="#116d70" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />}
      {points.map(({ at, x, y }) => <circle key={at} cx={x} cy={y} r={at === latest.at ? 3.5 : 2} fill="#116d70" />)}
    </svg>
    <div className="flex justify-between pl-[29px] pr-2 text-[11px] tabular-nums" aria-hidden="true">
      {single ? <time className="mx-auto" dateTime={new Date(latest.at).toISOString()}>{lastTime}</time>
        : <><time dateTime={new Date(first.at).toISOString()}>{firstTime}</time>
          <time dateTime={new Date(latest.at).toISOString()}>{lastTime}</time></>}
    </div>
  </>;
}
