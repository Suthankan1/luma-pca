"use client";

import { PanelRetry } from "@/components/staff/PanelRetry";

import Link from "next/link";
import { useState, type CSSProperties } from "react";
import { withParams } from "@/lib/insights";
import { studentRate, type MonthlyStudent } from "@/lib/student-month";

function rateLabel(percent: number | null) {
  return percent == null ? "—" : `${Math.round(percent)}%`;
}

export function MonthlyStudentDirectory({ students, classTypes, month, attendanceFailed, initialQuery, initialType, initialSort }: {
  students: MonthlyStudent[]; classTypes: { key: string; label: string }[];
  month: string; attendanceFailed: boolean; initialQuery: string; initialType: string; initialSort: string;
}) {
  const [search, setSearch] = useState(initialQuery);
  const [type, setType] = useState(initialType);
  const [sort, setSort] = useState(initialSort);
  const terms = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const rows = students.filter(student => {
    if (type && !Object.hasOwn(student.types, type)) return false;
    const searchable = `${student.fullName} ${student.studentNumber}`.toLowerCase();
    return terms.every(term => searchable.includes(term));
  }).map(student => ({ ...student, percent: studentRate(student, type) }));
  rows.sort((a, b) => {
    const byName = a.fullName.localeCompare(b.fullName) || a.studentNumber.localeCompare(b.studentNumber);
    if (sort === "name" || a.percent === b.percent) return byName;
    if (a.percent == null) return 1;
    if (b.percent == null) return -1;
    return sort === "low" ? a.percent - b.percent : b.percent - a.percent;
  });
  const scored = rows.filter(row => row.percent != null);
  const average = scored.length ? scored.reduce((sum, row) => sum + (row.percent ?? 0), 0) / scored.length : null;
  const detailHref = (id: string) => withParams(`/staff/students/${id}/attendance`, { month, q: search.trim(), type, sort });

  return <>
    <section className="panel">
      <div className="student-month-average" aria-live="polite">
        <div><span>Overall average attendance</span><strong>{rateLabel(average)}</strong></div>
        <p>{rows.length} {rows.length === 1 ? "student" : "students"} · {scored.length} with attendance data</p>
      </div>
      <p className="panel-sub">Attendance measures the share of completed, tracked sessions a student was expected in and joined.</p>
      {attendanceFailed && <p className="session-error" role="alert">Monthly attendance couldn’t load. <PanelRetry /></p>}
    </section>
    <figure className="panel panel-gap">
      <figcaption className="panel-title">Attendance by student</figcaption>
      {rows.length ? <div className="student-month-chart" tabIndex={0} role="region" aria-label="Scrollable student attendance chart">
        <ul>
          {rows.map(row => <li key={row.id}
            title={`${row.fullName} · ${row.studentNumber} · ${rateLabel(row.percent)}`}
            aria-label={`${row.studentNumber}, ${row.percent == null ? "attendance unavailable" : rateLabel(row.percent)}`}>
            <div className="student-month-track" aria-hidden="true">
              {row.percent != null && <span className="classes-chart-fill" style={{ "--attendance": `${row.percent}%` } as CSSProperties} />}
              <span className="classes-chart-value">{rateLabel(row.percent)}</span>
            </div>
            <span className="student-month-reg" aria-hidden="true">{row.studentNumber}</span>
          </li>)}
        </ul>
      </div> : <p className="panel-sub">No matching students.</p>}
    </figure>
    <section className="panel panel-gap" aria-labelledby="student-month-directory">
      <h2 id="student-month-directory" className="panel-title">Student directory</h2>
      <div className="class-student-tools student-month-tools">
        <label>Search students
          <input type="search" value={search} maxLength={100} placeholder="Name or registration ID" onChange={event => setSearch(event.target.value)} />
        </label>
        <label>Class type
          <select value={type} onChange={event => setType(event.target.value)}>
            <option value="">All class types</option>
            {classTypes.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
          </select>
        </label>
        <label>Sort by
          <select value={sort} onChange={event => setSort(event.target.value)}>
            <option value="name">Name A–Z</option>
            <option value="low">Attendance low to high</option>
            <option value="high">Attendance high to low</option>
          </select>
        </label>
      </div>
      <div className="student-month-list" tabIndex={0} role="region" aria-label="Scrollable student directory">
        {rows.length ? <ul className="dir-list">
          {rows.map(row => <li key={row.id} className="student-month-row">
            <Link href={detailHref(row.id)} prefetch={false}>
              <span><strong>{row.fullName}</strong><small>{row.studentNumber}</small></span>
              <strong className="class-attendance-percent" aria-label={row.percent == null ? "Attendance unavailable" : `${rateLabel(row.percent)} attendance`}>{rateLabel(row.percent)}</strong>
            </Link>
          </li>)}
        </ul> : <p className="panel-sub">No students match these filters.</p>}
      </div>
    </section>
  </>;
}
