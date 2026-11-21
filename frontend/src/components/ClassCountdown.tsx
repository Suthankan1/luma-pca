"use client";

import { useEffect, useState } from "react";

export function ClassCountdown({ startsAt, now }: { startsAt: string; now: number }) {
  const start = Date.parse(startsAt);
  const [clock, setClock] = useState(now);
  useEffect(() => {
    const tick = () => {
      const time = Date.now();
      if (document.visibilityState === "visible") setClock(time);
      if (time >= start && document.visibilityState === "visible") window.clearInterval(timer);
    };
    const timer = window.setInterval(tick, 1000);
    document.addEventListener("visibilitychange", tick);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [start]);

  const remaining = Math.max(0, Math.ceil((start - clock) / 1000));
  if (remaining === 0) return <p className="student-countdown-label">Scheduled start reached</p>;
  const days = Math.floor(remaining / 86400);
  const hours = Math.floor(remaining / 3600) % 24;
  const minutes = Math.floor(remaining / 60) % 60;
  const seconds = remaining % 60;
  const units = [...(days ? [{ label: "days", value: days }] : []),
    { label: "hours", value: hours }, { label: "min", value: minutes }, { label: "sec", value: seconds }];

  return <div className="student-countdown" role="timer" aria-label="Time until the next scheduled class">
    {units.map(({ label, value }) => <span key={label}><b>{String(value).padStart(2, "0")}</b><small>{label}</small></span>)}
  </div>;
}
