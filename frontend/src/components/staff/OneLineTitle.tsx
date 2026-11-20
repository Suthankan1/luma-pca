"use client";

import { useEffect, useRef } from "react";

/**
 * A title kept to one line. The full text stays in the DOM (it is the link's accessible name, and what copy and
 * find-in-page see); when it does not fit, hovering or focusing the surrounding link shows it in full in a small card.
 * Escape hides that card until the pointer or focus leaves. On touch, the link's own page shows the full title.
 */
export function OneLineTitle({ text }: { text: string }) {
  const holder = useRef<HTMLSpanElement>(null);
  const line = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const box = holder.current, label = line.current;
    if (!box || !label) return;
    const check = () => box.toggleAttribute("data-truncated", label.scrollWidth > label.clientWidth + 1);
    const observer = new ResizeObserver(check);
    observer.observe(label);
    check();
    const trigger = box.closest("a, button");
    const dismiss = (event: KeyboardEvent) => { if (event.key === "Escape") box.setAttribute("data-dismissed", ""); };
    const reset = () => box.removeAttribute("data-dismissed");
    document.addEventListener("keydown", dismiss);
    trigger?.addEventListener("pointerleave", reset);
    trigger?.addEventListener("focusout", reset);
    return () => {
      observer.disconnect();
      document.removeEventListener("keydown", dismiss);
      trigger?.removeEventListener("pointerleave", reset);
      trigger?.removeEventListener("focusout", reset);
    };
  }, [text]);

  return <span className="one-line" ref={holder}>
    <span className="one-line-text" ref={line}>{text}</span>
    {/* Repeats the name for sighted users only; screen readers already read it once. */}
    <span className="one-line-full" aria-hidden="true">{text}</span>
  </span>;
}
