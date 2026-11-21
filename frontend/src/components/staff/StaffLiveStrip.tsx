"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import type { LiveClassSnapshot } from "@/lib/staff-live";
import { entryStateLabels, startedAgoLabel, timeRange, type DayEntry } from "@/lib/staff-home";
import { LiveOnlineChart } from "./LiveOnlineChart";
import { OneLineTitle } from "./OneLineTitle";
import { useLivePoll } from "./useLivePoll";

export function StaffLiveStrip({ entries, initial }: { entries: DayEntry[]; initial: LiveClassSnapshot[] }) {
  const router = useRouter();
  const [paused, setPaused] = useState(false);
  const [updatesFailed, setUpdatesFailed] = useState(false);
  const [snapshots, setSnapshots] = useState<Record<string, LiveClassSnapshot>>({});
  const query = new URLSearchParams(initial.map(row => ["session", row.sessionId])).toString();

  const networkFailed = useLivePoll<LiveClassSnapshot[]>(`/staff/live?${query}`, !paused, rows => {
    const available = rows.filter(row => row.numbers !== null);
    setUpdatesFailed(available.length !== rows.length);
    setSnapshots(prior => ({ ...prior, ...Object.fromEntries(available.map(row => [row.sessionId, row])) }));
    const classEnded = rows.some(row => row.ended);
    if (classEnded) router.refresh();
    return classEnded;
  });

  return <section className="home-strip home-strip-live" aria-labelledby="live-title">
    <h2 id="live-title" className="sr-only">Live now</h2>
    {entries.map((entry, index) => <LiveRow key={entry.key} entry={entry} snapshot={snapshots[initial[index].sessionId] ?? initial[index]} />)}
    <p className="muted live-refresh">Updating every 30 seconds while a class runs ·{" "}
      <button type="button" className="text-link" onClick={() => setPaused(value => !value)}>{paused ? "Resume" : "Pause"}</button>
      {(networkFailed || updatesFailed) && <span role="status"> · Live updates delayed</span>}
    </p>
  </section>;
}

function LiveRow({ entry, snapshot }: { entry: DayEntry; snapshot: LiveClassSnapshot }) {
  const { numbers, history, sampledAt, sessionId } = snapshot;
  return <article className="live-row live-row-chart" aria-label={`${entryStateLabels[entry.state]}: ${entry.title}`}>
    <span className={`live-badge ${entry.state === "live" ? "" : "live-badge-now"}`}>
      <span className="live-dot" aria-hidden="true" />{entry.state === "live" ? "Live" : "In progress"}
    </span>
    <div className="live-row-main">
      <h3><Link href={entry.href}><OneLineTitle text={entry.title} /></Link></h3>
      <p>{timeRange(entry)} · {startedAgoLabel(entry.startsAt, sampledAt)}</p>
    </div>
    {numbers ? <dl className="live-figures">
      <div><dt>Joined</dt><dd>{numbers.joined}<small> / {numbers.expected}</small></dd></div>
      <div><dt>Attendance</dt><dd>{numbers.percent == null ? "—" : `${numbers.percent}%`}</dd></div>
      <div><dt>Students online</dt><dd>{history?.length === 0 ? "—" : numbers.connectedNow}</dd></div>
    </dl> : <p className="live-row-note">Live numbers couldn’t load.</p>}
    <div className="live-online-slot">
      <LiveOnlineChart sessionId={sessionId} history={history} sampledAt={sampledAt} startsAt={entry.startsAt} />
    </div>
  </article>;
}
