"use client";

import { useState } from "react";

/** Builds the batch export link from the chosen dates; a download link works the same on phones and desktops. */
export function AttendanceExportForm({ batchId, from, to }: { batchId: string; from: string; to: string }) {
  const [range, setRange] = useState({ from, to });
  const ready = /^\d{4}-\d{2}-\d{2}$/.test(range.from) && /^\d{4}-\d{2}-\d{2}$/.test(range.to);
  return <div className="export-range">
    <label htmlFor="export-from">From</label>
    <input id="export-from" type="date" required value={range.from} onChange={e => setRange(r => ({ ...r, from: e.target.value }))} />
    <label htmlFor="export-to">To</label>
    <input id="export-to" type="date" required value={range.to} onChange={e => setRange(r => ({ ...r, to: e.target.value }))} />
    {ready ? <a className="action secondary" download
      href={`/staff/batches/${batchId}/attendance-export?${new URLSearchParams(range)}`}>Download CSV</a>
      : <span className="muted">Choose both dates.</span>}
  </div>;
}
