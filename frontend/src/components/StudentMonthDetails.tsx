"use client";

import Link from "next/link";
import { useState, type CSSProperties, type ReactNode } from "react";
import { formatDay, formatDuration, formatClock, outcomeLabels, type ClassAttendance } from "@/lib/insights";
import { classTypeKey, compactClassTitle } from "@/lib/staff-home";
import { attendancePercentFor, participationFor, type Participation } from "@/lib/student-month";

export function StudentMonthDetails({ types, sessions, enrolments, staff }: {
  types: { key: string; title: string; counts: Participation }[]; sessions: ClassAttendance[];
  enrolments: ReactNode; staff?: boolean;
}) {
  const [type, setType] = useState<string | null>(null);
  const visible = type ? sessions.filter(session => classTypeKey(session.title) === type) : sessions;
  return <>
    <div className="profile-panels">
      <section className="panel profile-chart" aria-labelledby="profile-chart-title">
        <h2 id="profile-chart-title" className="panel-title">Attendance by class type</h2>
        {types.length ? <ul className="profile-bars">{types.map(item => {
          const rate = attendancePercentFor(item.counts);
          const value = rate == null ? "—" : `${Math.round(rate)}%`;
          return <li key={item.key}>
            <button type="button" aria-pressed={type === item.key} title={`${item.title} · ${value}`}
              aria-label={rate == null ? `— ${item.title}, attendance unavailable` : `${value} ${item.title}`}
              onClick={() => setType(current => current === item.key ? null : item.key)}>
              <strong>{value}</strong>
              <span className="profile-bar-track" aria-hidden="true"><span className="profile-bar-fill"
                style={{ "--profile-rate": `${rate ?? 0}%` } as CSSProperties} /></span>
              <span className="profile-bar-label">{item.title}</span>
            </button>
          </li>;
        })}</ul> : <p className="panel-sub">No completed sessions this month.</p>}
      </section>
      <section className="panel" aria-labelledby="profile-enrolments-title">
        <h2 id="profile-enrolments-title" className="panel-title">Enrolled this month</h2>
        {enrolments}
      </section>
    </div>
    <section className="panel profile-history" aria-labelledby="profile-history-title">
      <header><h2 id="profile-history-title" className="panel-title">Class history</h2>
        {type && <button type="button" className="text-link" onClick={() => setType(null)}>Show all classes</button>}
      </header>
      <p className="sr-only" aria-live="polite">{visible.length} sessions shown</p>
      {visible.length ? <ul className="profile-class-list">{visible.map(session => {
        const { expected, joined } = participationFor(session, session);
        const status = session.outcome ? outcomeLabels[session.outcome] ?? session.outcome
          : !expected ? "Attendance unavailable" : joined ? "Joined · pending" : "No recorded join · pending";
        return <li key={session.id}>
          <div className="profile-class-row">
            <div>
              {staff ? <Link href={`/staff/sessions/${session.id}`} className="text-link" prefetch={false}>
                {compactClassTitle(session.title)}</Link> : <strong>{compactClassTitle(session.title)}</strong>}
              <time dateTime={session.startsAt}>{formatDay(session.startsAt, session.timeZone)}</time>
            </div>
            <span className="profile-duration">{expected ? `${formatDuration(session.connectedSeconds)} connected` : "—"}</span>
            <span className="profile-outcome" data-outcome={session.outcome ?? "pending"}>{status}</span>
          </div>
          {session.timeline?.length > 0 && <details className="profile-visits">
            <summary>{session.timeline.length === 1 ? "Connection details" : `${session.timeline.length} connections`}</summary>
            <ul>{session.timeline.map((visit, index) => <li key={`${visit.joinedAt}-${index}`}>
              {formatClock(visit.joinedAt, session.timeZone)} – {formatClock(visit.leftAt, session.timeZone)} · {formatDuration(visit.secondsInClass)}
            </li>)}</ul>
          </details>}
        </li>;
      })}</ul> : <p className="panel-sub">No sessions to show.</p>}
    </section>
  </>;
}
