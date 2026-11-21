"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

/**
 * Re-renders the page from the server every `seconds` while `until` has not passed and the tab is
 * visible, so live attendance and engagement counts stay current during a class without a push channel.
 */
export function LiveRefresh({ until, seconds = 30, label = "while the class runs" }: { until?: string; seconds?: number; label?: string }) {
  const router = useRouter();
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (paused) return;
    const end = until ? Date.parse(until) : Infinity;
    const timer = window.setInterval(() => {
      if (Date.now() > end) { window.clearInterval(timer); return; }
      if (document.visibilityState === "visible") router.refresh();
    }, seconds * 1000);
    return () => window.clearInterval(timer);
  }, [router, until, seconds, paused]);
  return <p className="muted live-refresh">Updating every {seconds} seconds {label} ·{" "}
    <button type="button" className="text-link" onClick={() => setPaused(p => !p)}>{paused ? "Resume" : "Pause"}</button></p>;
}
