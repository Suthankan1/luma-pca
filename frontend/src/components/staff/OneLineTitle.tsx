"use client";

import { useEffect, useRef } from "react";

/**
 * A title kept to one line. Titles with extra pipe-separated details show their first two parts. The full text is
 * the link's accessible name and appears on hover or keyboard focus when the visible title is shortened or clipped.
 * Escape hides that card until the pointer or focus leaves. On touch, the link's own page shows the full title.
 */
export function OneLineTitle({ text }: { text: string }) {
  const parts = text.split("|", 3).map(part => part.trim());
  const displayText = parts.length === 3 && parts[0] && parts[1] ? `${parts[0]} | ${parts[1]}` : text;
  const shortened = displayText !== text;
  const line = useRef<HTMLSpanElement>(null);
  const full = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const label = line.current, tip = full.current;
    const trigger = label?.closest<HTMLElement>("a, button");
    if (!label || !tip || !trigger) return;
    if (typeof tip.showPopover !== "function") {
      if (trigger.hasAttribute("title")) return;
      trigger.setAttribute("title", text);
      return () => trigger.removeAttribute("title");
    }

    let hovered = false;
    let dismissed = false;
    let closeTimer: number | undefined;
    const isOpen = () => tip.matches(":popover-open");

    const position = (anchor: DOMRect) => {
      const { clientWidth: width, clientHeight: height } = document.documentElement;
      const edge = 8, gap = 6;
      tip.style.setProperty("--tip-available-width", `${width - edge * 2}px`);
      const left = Math.min(Math.max(edge, anchor.left), width - tip.offsetWidth - edge);
      const above = anchor.top - tip.offsetHeight - gap;
      const below = anchor.bottom + gap;
      const top = above >= edge ? above : Math.max(edge, Math.min(below, height - tip.offsetHeight - edge));
      tip.style.left = `${left}px`;
      tip.style.top = `${top}px`;
    };

    const update = () => {
      const active = hovered || tip.matches(":hover") || trigger.matches(":focus-visible");
      if (!active) dismissed = false;
      if (active && !dismissed && (shortened || label.scrollWidth > label.clientWidth + 1)) {
        const anchor = label.getBoundingClientRect();
        if (anchor.height > 0 && anchor.bottom > 0 && anchor.top < document.documentElement.clientHeight) {
          if (!isOpen()) tip.showPopover();
          position(anchor);
          return;
        }
      }
      if (isOpen()) tip.hidePopover();
    };

    const enter = (event: PointerEvent) => {
      if (event.pointerType === "touch") return;
      hovered = true;
      window.clearTimeout(closeTimer);
      update();
    };
    const leave = () => {
      hovered = false;
      window.clearTimeout(closeTimer);
      // Leave time to move the pointer across the gap onto the tooltip.
      closeTimer = window.setTimeout(update, 150);
    };
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || !isOpen()) return;
      dismissed = true;
      update();
    };

    const observer = new ResizeObserver(update);
    observer.observe(label);
    trigger.addEventListener("pointerenter", enter);
    trigger.addEventListener("pointerleave", leave);
    trigger.addEventListener("focus", update);
    trigger.addEventListener("blur", update);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    document.addEventListener("keydown", dismiss);
    return () => {
      observer.disconnect();
      window.clearTimeout(closeTimer);
      trigger.removeEventListener("pointerenter", enter);
      trigger.removeEventListener("pointerleave", leave);
      trigger.removeEventListener("focus", update);
      trigger.removeEventListener("blur", update);
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
      document.removeEventListener("keydown", dismiss);
      if (isOpen()) tip.hidePopover();
    };
  }, [shortened, text]);

  // The popover sits in the browser's top layer, above adjacent cards and layout containers.
  return <span className="one-line">
    <span className="one-line-text" ref={line} aria-hidden={shortened || undefined}>{displayText}</span>
    {shortened && <span className="sr-only">{text}</span>}
    {/* Repeats the full title for sighted users only. */}
    <span className="one-line-full" popover="manual" aria-hidden="true" ref={full}>{text}</span>
  </span>;
}
