"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

/**
 * Re-renders the page from the server every `seconds` while `until` has not passed and the tab is
 * visible, so live attendance and engagement counts stay current during a class without a push channel.
 */
export function LiveRefresh({ until, seconds = 30, label = "while the class runs", dashboard = false }: {
  until?: string; seconds?: number; label?: string; dashboard?: boolean;
}) {
  const router = useRouter();
  const [paused, setPaused] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  useEffect(() => {
    if (paused) return;
    const end = until ? Date.parse(until) : Infinity;
    const timer = window.setInterval(() => {
      if (Date.now() > end) { window.clearInterval(timer); return; }
      if (document.visibilityState === "visible") router.refresh();
    }, seconds * 1000);
    return () => window.clearInterval(timer);
  }, [router, until, seconds, paused]);
  if (dashboard) return <div className="student-live-refresh">
    <span data-paused={paused} role="status">{paused ? "Auto-refresh paused" : `Auto-refresh · ${seconds}s`}</span>
    <button type="button" disabled={refreshing} onClick={() => startRefresh(() => router.refresh())}>
      {refreshing ? "Refreshing…" : "Refresh now"}
    </button>
    <button type="button" onClick={() => setPaused(value => !value)}>{paused ? "Resume" : "Pause"}</button>
  </div>;
  return <p className="muted live-refresh">Updating every {seconds} seconds {label} ·{" "}
    <button type="button" className="text-link" onClick={() => setPaused(p => !p)}>{paused ? "Resume" : "Pause"}</button></p>;
}
