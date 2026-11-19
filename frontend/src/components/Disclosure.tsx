import type { ReactNode } from "react";

/**
 * Progressive disclosure on the native <details>/<summary> pair: keyboard (Enter/Space), screen readers (expanded state)
 * and find-in-page work without script, and it renders on the server. Use it for secondary forms, settings, history and
 * long explanations, never to hide an action people need every time.
 *
 * @param summary the visible trigger text: say what opens ("Manage webinar", "Run history (4)")
 * @param hint optional short text after the label (a count or state)
 */
export function Disclosure({ summary, hint, defaultOpen = false, className = "", children, id }: {
  summary: ReactNode; hint?: ReactNode; defaultOpen?: boolean; className?: string; children: ReactNode; id?: string;
}) {
  return <details className={`disclosure ${className}`} open={defaultOpen || undefined} id={id}>
    <summary className="disclosure-summary">
      <span className="disclosure-label">{summary}</span>
      {hint !== undefined && <span className="disclosure-hint">{hint}</span>}
      <span className="disclosure-chevron" aria-hidden />
    </summary>
    <div className="disclosure-body">{children}</div>
  </details>;
}
