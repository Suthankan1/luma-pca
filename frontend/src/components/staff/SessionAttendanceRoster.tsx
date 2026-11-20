"use client";

import Link from "next/link";
import { useState } from "react";
import { attendanceOutcomes, formatDuration, formatInstant, outcomeLabels, type AttendanceRow } from "@/lib/staff-forms";

const finalFilters = attendanceOutcomes.filter(outcome => outcome !== "EXCUSED");

export function SessionAttendanceRoster({ students, sessionId, timeZone, finalised }: {
  students: AttendanceRow[];
  sessionId: string;
  timeZone: string;
  finalised: boolean;
}) {
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [order, setOrder] = useState("roster");
  const query = search.trim().toLowerCase();
  const activeStatus = finalised && (status === "seen" || status === "unseen") ? "all" : status;
  const rows = students.filter(student => {
    const value = student.outcome ?? (finalised ? null : student.seen ? "seen" : "unseen");
    return (activeStatus === "all" || value === activeStatus)
      && (student.fullName.toLowerCase().includes(query) || student.studentNumber.toLowerCase().includes(query));
  });
  if (order !== "roster") rows.sort((a, b) => {
    const byName = a.fullName.localeCompare(b.fullName) || a.studentNumber.localeCompare(b.studentNumber);
    if (order === "name") return byName;
    const byTime = a.connectedSeconds - b.connectedSeconds;
    return (order === "shortest" ? byTime : -byTime) || byName;
  });

  return <>
    <div className="class-student-tools session-roster-tools">
      <label>Search students
        <input type="search" placeholder="Name or reg. no." value={search} onChange={event => setSearch(event.target.value)} />
      </label>
      <label>Sort by
        <select value={order} onChange={event => setOrder(event.target.value)}>
          <option value="roster">Roster order</option>
          <option value="name">Name A–Z</option>
          <option value="longest">Longest connected</option>
          <option value="shortest">Shortest connected</option>
        </select>
      </label>
      <label className="session-roster-filter">Attendance
        <select value={activeStatus} onChange={event => setStatus(event.target.value)}>
          <option value="all">All statuses</option>
          {finalised ? finalFilters.map(value => <option key={value} value={value}>{outcomeLabels[value]}</option>)
            : <>
              <option value="seen">Seen</option>
              <option value="unseen">Not seen</option>
            </>}
        </select>
      </label>
      <a className="text-link session-roster-export" href={`/staff/sessions/${sessionId}/attendance-export`} download>Download CSV</a>
    </div>
    <div className="session-roster-head" aria-hidden="true"><span>Student</span><span>Time connected</span><span>Attendance</span></div>
    <div className="session-roster-scroll" role="region" aria-label="Student attendance results" tabIndex={0}>
      {rows.length === 0 ? <p className="dir-empty muted" role="status">
        {students.length === 0 ? "No student records for this session." : "No students match these filters."}
      </p> : <ul className="session-roster">
        {rows.map(a => <li key={a.studentId} className="session-roster-row" aria-label={`Attendance ${a.studentNumber} ${a.fullName}`}>
          <div className="session-roster-person">
            <Link href={`/staff/students/${a.studentId}`} className="text-link">{a.fullName}</Link>
            <small>{a.studentNumber}{!a.expected && " · not expected at this class"}</small>
          </div>
          <div className="session-roster-time">{a.seen
            ? <><strong>{a.connectedSeconds > 0 && a.connectedSeconds < 60 ? `${a.connectedSeconds} sec` : formatDuration(a.connectedSeconds)} connected</strong>
                <small>{a.firstJoinAt && <>first joined {formatInstant(a.firstJoinAt, timeZone)}
                  {a.secondsAfterStart ? ` (${formatDuration(a.secondsAfterStart)} after the start)` : ""}
                  {" · "}</>}{a.visits} visit{a.visits === 1 ? "" : "s"}{a.openVisits > 0 ? " · no leave received yet" : ""}</small></>
            : <span className="muted">No recorded join</span>}
          </div>
          <div className="session-roster-status">
            {a.outcome ? <span className={`status outcome ${a.outcome === "ABSENT" ? "off" : a.outcome === "EXCUSED" ? "" : "on"}`}>{outcomeLabels[a.outcome]}</span>
              : <span className="status">{finalised ? "No final record" : a.seen ? "Seen" : "Not seen"}</span>}
          </div>
        </li>)}
      </ul>}
    </div>
  </>;
}
