"use client";

import { useState } from "react";
import type { ReportKind } from "@/lib/report-period";

type Option = { value: string; label: string; detail?: string };
const registrationKey = (text: string) => text.toLowerCase().replace(/[^a-z0-9]/g, "");

export function ReportSelection({ kind, title, label, options, empty, month, endMonth }: {
  kind: ReportKind; title: string; label: string; options: Option[] | null; empty: string; month: string; endMonth: string;
}) {
  const [search, setSearch] = useState("");
  const [selection, setSelection] = useState("");
  const terms = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const selected = options?.find(option => option.value === selection);
  const matches = options?.filter(option => {
    const searchable = `${option.label} ${option.detail ?? ""}`.toLowerCase();
    const registration = kind === "student" ? registrationKey(option.detail ?? "") : "";
    return terms.every(term => {
      const key = registrationKey(term);
      return searchable.includes(term) || (key !== "" && registration.includes(key));
    });
  }) ?? [];
  const id = `report-${kind}`;

  return <section className="panel" aria-labelledby={id}>
    <h2 className="panel-title" id={id}>{title}</h2>
    {options === null ? <p className="session-error" role="alert">The options couldn’t load.</p>
      : options.length === 0 ? <p className="panel-sub">{empty}</p>
      : <form className="report-selection" action="/staff/reports/download">
        <input type="hidden" name="kind" value={kind} />
        <input type="hidden" name="month" value={month} />
        <input type="hidden" name="endMonth" value={endMonth} />
        {selected && !matches.includes(selected) && <input type="hidden" name="selection" value={selection} />}
        <label htmlFor={`${id}-search`}>Search {kind === "student" ? "students" : kind === "session" ? "sessions" : "class types"}</label>
        <input id={`${id}-search`} type="search" value={search} maxLength={100}
          placeholder={kind === "student" ? "Name or registration ID" : kind === "session" ? "Class name or date" : "Class name"}
          onChange={event => setSearch(event.target.value)} />
        <fieldset className="report-record-list">
          <legend className="sr-only">Choose {label.toLowerCase()}</legend>
          {matches.length ? matches.map(option => <label key={option.value} className="report-record">
            <input type="radio" name="selection" value={option.value} checked={option.value === selection}
              onChange={() => setSelection(option.value)} />
            <span><strong>{option.label}</strong>{option.detail && <small>{option.detail}</small>}</span>
          </label>) : <p className="panel-sub report-no-results" role="status">No matching records.</p>}
        </fieldset>
        <div className="report-selection-footer">
          {selected && <p className="panel-sub">Selected <strong>{selected.label}</strong>{selected.detail && ` · ${selected.detail}`}</p>}
          <div className="report-download-actions">
            <button className="action" type="submit" name="format" value="pdf" disabled={!selected}>Download PDF</button>
            <button className="action secondary" type="submit" name="format" value="xlsx" disabled={!selected}>Download Excel</button>
          </div>
        </div>
      </form>}
  </section>;
}
