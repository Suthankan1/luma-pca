import Link from "next/link";
import { presets, withParams } from "@/lib/insights";

/** Quick date ranges and a from/to form; {@code keep} carries other filters (course, batch, view) along. */
export function RangeFilter({ path, from, to, today, keep = {} }: {
  path: string; from: string; to: string; today: string; keep?: Record<string, string | null>;
}) {
  return <div className="range">
    <nav className="chips" aria-label="Period">
      {presets(today).map(p => {
        const on = p.from === from && p.to === to;
        return <Link key={p.key} className={`chip ${on ? "on" : ""}`} aria-current={on ? "true" : undefined}
          href={withParams(path, { ...keep, from: p.from, to: p.to })}>{p.label}</Link>;
      })}
    </nav>
    <form method="get" action={path} className="range-form" aria-label="Choose dates">
      {Object.entries(keep).map(([k, v]) => v ? <input key={k} type="hidden" name={k} value={v} /> : null)}
      <label htmlFor="range-from">From</label>
      <input id="range-from" name="from" type="date" defaultValue={from} max={today} required />
      <label htmlFor="range-to">To</label>
      <input id="range-to" name="to" type="date" defaultValue={to} max={today} required />
      <button className="action secondary" type="submit">Show</button>
    </form>
  </div>;
}
