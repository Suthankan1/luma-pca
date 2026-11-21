"use client";

import { useRouter } from "next/navigation";
import { useEffect, useEffectEvent, useState } from "react";

const REFRESH_MS = 30000;

/** Updates one visible panel without overlapping requests. A true callback result ends polling. */
export function useLivePoll<T>(path: string, active: boolean, onData: (data: T) => boolean, until?: number) {
  const router = useRouter();
  const [failed, setFailed] = useState(false);
  const receive = useEffectEvent(onData);
  useEffect(() => {
    if (!active) return;
    const abort = new AbortController();
    let timer: number;
    let running = false;
    let finished = false;

    async function poll() {
      if (running || finished || abort.signal.aborted || (until != null && Date.now() > until)) return;
      if (document.visibilityState === "hidden") return;
      window.clearTimeout(timer);
      running = true;
      try {
        const response = await fetch(path, { cache: "no-store", redirect: "manual", signal: abort.signal });
        if (response.type === "opaqueredirect" || response.status === 401 || response.status === 403) {
          finished = true;
          router.refresh();
          return;
        }
        if (!response.ok) throw new Error("Live request failed");
        const data: T = await response.json();
        if (abort.signal.aborted) return;
        setFailed(false);
        finished = receive(data);
      } catch {
        if (!abort.signal.aborted) setFailed(true);
      } finally {
        running = false;
        if (!finished && !abort.signal.aborted) timer = window.setTimeout(poll, REFRESH_MS);
      }
    }

    timer = window.setTimeout(poll, REFRESH_MS);
    document.addEventListener("visibilitychange", poll);
    return () => {
      abort.abort();
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", poll);
    };
  }, [active, path, router, until]);
  return failed;
}
