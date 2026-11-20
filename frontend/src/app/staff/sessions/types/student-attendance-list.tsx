"use client";

import { useState } from "react";

type Student = { studentId: string; studentName: string; studentNumber: string; percent: number | null };

export function StudentAttendanceList({ students }: { students: Student[] }) {
  const [search, setSearch] = useState("");
  const [order, setOrder] = useState("name");
  const query = search.trim().toLowerCase();
  const matches = students.filter(student =>
    student.studentName.toLowerCase().includes(query) || student.studentNumber.toLowerCase().includes(query));
  matches.sort((a, b) => {
    const nameOrder = a.studentName.localeCompare(b.studentName) || a.studentNumber.localeCompare(b.studentNumber);
    if (order === "name" || a.percent === b.percent) return nameOrder;
    if (a.percent == null) return 1;
    if (b.percent == null) return -1;
    return order === "low" ? a.percent - b.percent : b.percent - a.percent;
  });

  return <>
    <div className="class-student-tools">
      <label>Search students
        <input type="search" placeholder="Name or reg. no." value={search} onChange={event => setSearch(event.target.value)} />
      </label>
      <label>Sort by
        <select value={order} onChange={event => setOrder(event.target.value)}>
          <option value="name">Name A–Z</option>
          <option value="low">Attendance: low to high</option>
          <option value="high">Attendance: high to low</option>
        </select>
      </label>
    </div>
    <div className="classes-panel-scroll" role="region" aria-label="Student attendance" tabIndex={0}>
      {matches.length === 0 ? <p className="dir-empty muted" role="status">
        {query ? "No students match your search." : "No active enrolments for this class type."}
      </p> : <ul>{matches.map(student => <li className="class-detail-row" key={student.studentId}>
        <div><span>{student.studentName}</span><small>{student.studentNumber}</small></div>
        <strong className="class-attendance-percent">{student.percent == null ? "—" : `${student.percent}%`}</strong>
      </li>)}</ul>}
    </div>
  </>;
}
