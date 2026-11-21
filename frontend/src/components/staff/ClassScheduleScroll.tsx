"use client";

import { useLayoutEffect, useRef, type ReactNode } from "react";

export function ClassScheduleScroll({ anchorDay, children }: { anchorDay: string; children: ReactNode }) {
  const scrollRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const panel = scrollRef.current;
    const scrollToAnchor = () => {
      const day = panel?.querySelector(`[data-schedule-day="${anchorDay}"]`);
      if (panel && day) {
        panel.scrollTop += day.getBoundingClientRect().top - panel.getBoundingClientRect().top - panel.clientTop;
      }
    };
    scrollToAnchor();
    const frame = requestAnimationFrame(scrollToAnchor);
    return () => cancelAnimationFrame(frame);
  }, [anchorDay]);

  return <div ref={scrollRef} className="classes-panel-scroll" tabIndex={0} role="region" aria-label="Monthly schedule">
    {children}
  </div>;
}
