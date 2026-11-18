"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { formatDuration, level, type StudentSummary } from "@/lib/insights";
import { PercentBar } from "./Kpis";

type Row = Omit<StudentSummary, "cells">;
type SortKey = "studentNumber" | "fullName" | "percent" | "connectedSeconds" | "joined" | "rejoins";

/**
 * Every student of the selection: search by ID or name, show only those below the rule or never seen, sort by any
 * column. A row opens the student's own page (owners and administrators).
 */
export function StudentTable({ rows, minPercent, studentHref }: {
  rows: Row[]; minPercent: number | null; studentHref: Record<string, string> | null;
}) {
  const [query, setQuery] = useState("");
  const [show, setShow] = useState<"all" | "below" | "never">("all");
  const [sort, setSort] = useState<{ key: SortKey; descending: boolean }>({ key: "studentNumber", descending: false });
  const target = minPercent ?? 75;

  const visible = useMemo(() => {
    const text = query.trim().toLowerCase();
    const filtered = rows.filter(r => (!text || r.studentNumber.toLowerCase().includes(text) || r.fullName.toLowerCase().includes(text))
      && (show === "all" || (show === "below" ? r.percent < target : r.joined === 0)));
    const sorted = [...filtered].sort((a, b) => {
      const x = a[sort.key];
      const y = b[sort.key];
      const order = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y));
      return sort.descending ? -order : order;
    });
    return sorted;
  }, [rows, query, show, sort, target]);

  const header = (key: SortKey, label: string, numeric = false) => {
    const active = sort.key === key;
    return <th scope="col" aria-sort={active ? (sort.descending ? "descending" : "ascending") : "none"} className={numeric ? "num" : undefined}>
      <button type="button" className="th-sort" onClick={() => setSort({ key, descending: active ? !sort.descending : numeric })}>
        {label}<span aria-hidden>{active ? (sort.descending ? " ↓" : " ↑") : ""}</span>
      </button>
    </th>;
  };

  const below = rows.filter(r => r.percent < target).length;
  const never = rows.filter(r => r.joined === 0).length;
  return <div>
    <div className="table-tools">
      <label className="sr-only" htmlFor="student-filter">Find a student</label>
      <input id="student-filter" type="search" placeholder="Find by student ID or name" value={query}
        onChange={e => setQuery(e.target.value)} className="table-search" />
      <label className="sort-select">Sort by{" "}
        <select value={`${sort.key}:${sort.descending ? "desc" : "asc"}`} onChange={e => {
          const [key, direction] = e.target.value.split(":");
          setSort({ key: key as SortKey, descending: direction === "desc" });
        }}>
          <option value="studentNumber:asc">Student ID</option>
          <option value="fullName:asc">Name</option>
          <option value="percent:desc">Attendance, highest first</option>
          <option value="percent:asc">Attendance, lowest first</option>
          <option value="connectedSeconds:desc">Time in webinars</option>
          <option value="rejoins:desc">Most rejoins</option>
        </select>
      </label>
      <div className="chips" role="group" aria-label="Show">
        {([["all", `All (${rows.length})`], ["below", `Below ${target}% (${below})`], ["never", `Never joined (${never})`]] as const)
          .map(([key, label]) => <button key={key} type="button" className={`chip ${show === key ? "on" : ""}`} aria-pressed={show === key}
            onClick={() => setShow(key)}>{label}</button>)}
      </div>
    </div>
    <div className="table-wrap">
      <table className="data-table" aria-label="Students">
        <thead><tr>
          {header("studentNumber", "Student ID")}
          {header("fullName", "Name")}
          <th scope="col">Batch</th>
          {header("joined", "Classes joined", true)}
          {header("connectedSeconds", "Time in webinars", true)}
          {header("percent", "Attendance", true)}
          <th scope="col" className="num">Present · Late · Absent</th>
          {header("rejoins", "Rejoins", true)}
        </tr></thead>
        <tbody>
          {visible.map(r => {
            const href = studentHref?.[r.studentId];
            return <tr key={r.studentId} className={href ? "row-link" : undefined}>
              <td data-label="Student ID"><span className="mono">{r.studentNumber}</span></td>
              <td data-label="Name">{href ? <Link className="text-link" href={href}>{r.fullName}</Link> : r.fullName}</td>
              <td data-label="Batch">{r.batchCodes.join(", ")}</td>
              <td data-label="Classes joined" className="num">{r.joined} / {r.classes}</td>
              <td data-label="Time in webinars" className="num">{formatDuration(r.connectedSeconds)}
                <span className="muted"> of {formatDuration(r.classSeconds)}</span></td>
              <td data-label="Attendance" className="num"><PercentBar percent={r.percent} tone={level(r.percent, minPercent, r.joined > 0)} /></td>
              <td data-label="Present · Late · Absent" className="num">{r.present + r.late + r.absent + r.excused === 0
                ? <span className="muted">not final</span> : `${r.present} · ${r.late} · ${r.absent}`}</td>
              <td data-label="Rejoins" className="num">{r.rejoins}</td>
            </tr>;
          })}
          {visible.length === 0 && <tr><td colSpan={8} className="muted empty-cell">No students match.</td></tr>}
        </tbody>
      </table>
    </div>
  </div>;
}
