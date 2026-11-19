/** A row of headline numbers. Each tile: a label, the value and an optional note. */
export function Kpis({ items, label, compact }: { items: { label: string; value: string; note?: string; tone?: "good" | "warn" | "bad" }[];
  label: string; compact?: boolean }) {
  return <ul className={`kpis ${compact ? "kpis-compact" : ""}`} aria-label={label}>
    {items.map(item => <li key={item.label} className={`kpi ${item.tone ? `kpi-${item.tone}` : ""}`} aria-label={`${item.label}: ${item.value}`}>
      <span className="kpi-label">{item.label}</span>
      <strong className="kpi-value">{item.value}</strong>
      {item.note && <span className="kpi-note">{item.note}</span>}
    </li>)}
  </ul>;
}

/** A share of class time as a bar and a number. */
export function PercentBar({ percent, tone }: { percent: number; tone: "good" | "warn" | "bad" | "none" }) {
  return <span className={`pbar pbar-${tone}`}>
    <span className="pbar-track" aria-hidden><span className="pbar-fill" style={{ width: `${Math.min(100, Math.max(0, percent))}%` }} /></span>
    <span className="pbar-value">{percent}%</span>
  </span>;
}
