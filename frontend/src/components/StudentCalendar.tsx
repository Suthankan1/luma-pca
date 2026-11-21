"use client";

import Link from "next/link";
import { useState } from "react";
import { longDayLabel, monthLabel, shiftMonth, type CalendarDay } from "@/lib/staff-home";
import { ACADEMY_TIME_ZONE } from "@/lib/staff-forms";
import { formatClock } from "@/lib/insights";
import type { TimetableEntry } from "@/lib/student-timetable";

const weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export function StudentCalendar({ month, today, selectedDay, weeks, entries }: {
  month: string; today: string; selectedDay: string; weeks: CalendarDay[][]; entries: TimetableEntry[];
}) {
  const [selected, setSelected] = useState(selectedDay);
  const byDay = new Map<string, TimetableEntry[]>();
  for (const entry of entries) {
    const day = byDay.get(entry.date);
    if (day) day.push(entry);
    else byDay.set(entry.date, [entry]);
  }
  const agenda = byDay.get(selected) ?? [];

  return <div className="student-calendar-grid">
    <section className="panel student-calendar" aria-label="Class calendar">
      <header className="student-calendar-controls">
        <h2 className="panel-title">{monthLabel(month)}</h2>
        <nav aria-label="Calendar months">
          <Link href={`?month=${shiftMonth(month, -1)}`} aria-label="Previous month">‹</Link>
          <Link href="/student/calendar" aria-label="Go to today">Today</Link>
          <Link href={`?month=${shiftMonth(month, 1)}`} aria-label="Next month">›</Link>
        </nav>
      </header>
      <div className="student-calendar-days">
        {weekdays.map(day => <span key={day} className="student-calendar-weekday">{day}</span>)}
        {weeks.flat().map(day => {
          const classes = byDay.get(day.date) ?? [];
          return <button key={day.date} type="button" data-outside={!day.inMonth} aria-current={day.date === today ? "date" : undefined}
            aria-pressed={day.date === selected} onClick={() => setSelected(day.date)}
            aria-label={`${longDayLabel(day.date)}, ${classes.length} ${classes.length === 1 ? "class" : "classes"}`}>
            <span>{Number(day.date.slice(-2))}</span>
            {classes.length > 0 && <span className="student-calendar-marks" aria-hidden="true">
              {classes.slice(0, 2).map(entry => <i key={entry.id} data-cancelled={entry.cancelled} />)}
              {classes.length > 2 && <small>+{classes.length - 2}</small>}
            </span>}
          </button>;
        })}
      </div>
    </section>
    <section className="panel student-agenda" aria-labelledby="student-agenda-title">
      <h2 id="student-agenda-title" className="panel-title">{longDayLabel(selected)}</h2>
      <div key={selected} className="student-portal-enter">
        {agenda.length === 0 ? <p className="panel-sub">No classes on this day.</p> : <ul>
          {agenda.map(entry => <li key={entry.id}>
            <time dateTime={entry.startsAt}>{formatClock(entry.startsAt, ACADEMY_TIME_ZONE)}
              {entry.endsAt && !entry.estimatedEnd && `–${formatClock(entry.endsAt, ACADEMY_TIME_ZONE)}`}</time>
            <strong>{entry.title}</strong>
            <span>{entry.batchCodes.join(", ")}{entry.cancelled && " · Cancelled"}</span>
            {!entry.cancelled && entry.date >= today && entry.joinUrl &&
              <a className="text-link" href={entry.joinUrl} target="_blank" rel="noopener noreferrer">Open in Zoom</a>}
          </li>)}
        </ul>}
      </div>
    </section>
  </div>;
}
