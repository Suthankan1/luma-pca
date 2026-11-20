import assert from "node:assert/strict";
import test from "node:test";
import { calendarEntries, chosenDay, chosenMonth, gridRange, homeHref, joinedLabel, liveNumbers, longDayLabel, monthLabel,
  monthWeeks, monthlyClassTypes, nextEntry, relativeDayName, shiftMonth, shortDayLabel, startedAgoLabel, startsInLabel, timeRange } from "../src/lib/staff-home.ts";

const session = (over) => ({ id: "s1", title: "Class", startsAt: "2026-11-20T10:30:00Z", endsAt: "2026-11-20T12:00:00Z",
  timeZone: "Asia/Colombo", localDate: "2026-11-20", status: "SCHEDULED", zoomWebinarId: null, batches: [], version: 0, origin: "LUMA", ...over });
const row = (expected, seen, openVisits = 0) => ({ studentId: String(Math.random()), studentNumber: "P", fullName: "S", expected, seen,
  visits: seen ? 1 : 0, openVisits, connectedSeconds: 0, firstJoinAt: null, secondsAfterStart: null });

test("month-to-date class types group title variants and show completed sessions newest first", () => {
  const title = "Model Paper Class | 2027 Batch";
  const groups = monthlyClassTypes([
    session({ id: "older", title: `${title} | Former suffix`, startsAt: "2026-11-19T10:30:00Z", endsAt: "2026-11-19T12:00:00Z" }),
    session({ id: "newer", title: `${title} | New suffix` }),
    session({ id: "other", title: "Model Paper Class | 2028 Batch" }),
    session({ id: "ongoing", title, endsAt: "2026-11-20T14:00:00Z" }),
    session({ id: "cancelled", title, status: "CANCELLED" }),
  ], Date.parse("2026-11-20T13:00:00Z"));
  assert.deepEqual(groups.map(([, group]) => [group.title, group.sessions.map(entry => entry.id)]),
    [[title, ["newer", "older"]], ["Model Paper Class | 2028 Batch", ["other"]]]);
});

test("the chosen day and month fall back to today and the day's month", () => {
  assert.equal(chosenDay("2026-02-30", "2026-11-20"), "2026-11-20");
  assert.equal(chosenDay(["2026-11-21"], "2026-11-20"), "2026-11-20");
  assert.equal(chosenDay("2026-11-21", "2026-11-20"), "2026-11-21");
  assert.equal(chosenMonth("2026-13", "2026-11-21"), "2026-11");
  assert.equal(chosenMonth("2027-01", "2026-11-21"), "2027-01");
  assert.equal(shiftMonth("2026-12", 1), "2027-01");
  assert.equal(shiftMonth("2026-01", -1), "2025-12");
  assert.equal(monthLabel("2026-11"), "November 2026");
  assert.equal(longDayLabel("2026-11-20"), "Friday 20 November 2026");
  assert.equal(shortDayLabel("2026-11-20"), "Fri 20 Nov");
});

test("the calendar is whole Monday-to-Sunday weeks, and one request covers its grid in academy time", () => {
  const weeks = monthWeeks("2026-11");
  assert.equal(weeks.length, 6);
  assert.deepEqual(weeks[0][0], { date: "2026-10-26", inMonth: false });
  assert.deepEqual(weeks[0][6], { date: "2026-11-01", inMonth: true });
  assert.deepEqual(weeks[5][6], { date: "2026-12-06", inMonth: false });
  assert.ok(weeks.every(week => week.length === 7));
  const range = gridRange("2026-11");
  assert.equal(range.fromInstant, "2026-10-25T18:30:00.000Z");
  assert.equal(range.toInstant, "2026-12-06T18:30:00.000Z");
  // February 2027 starts on a Monday and ends on a Sunday: exactly four weeks.
  assert.equal(monthWeeks("2027-02").length, 4);
});

test("classes and uncovered Zoom occurrences are grouped by day, soonest first, with their state", () => {
  const now = Date.parse("2026-11-20T11:00:00Z");
  const sessions = [
    session({ id: "later", title: "Later", startsAt: "2026-11-20T14:00:00Z", endsAt: "2026-11-20T15:00:00Z", zoomWebinarId: "844" }),
    session({ id: "running" }),
    session({ id: "done", startsAt: "2026-11-20T03:00:00Z", endsAt: "2026-11-20T04:00:00Z" }),
    session({ id: "gone", status: "CANCELLED" }),
  ];
  const monitors = [{ webinarId: "844", topic: "Zoom topic", timeZone: "Asia/Colombo", durationMinutes: 60, active: true, occurrences: [
    { occurrenceId: "1", startTime: "2026-11-20T14:20:00Z", durationMinutes: 60, status: "available" },  // the "Later" class
    { occurrenceId: "2", startTime: "2026-11-21T14:00:00Z", durationMinutes: 90, status: "available" },
    { occurrenceId: "3", startTime: "2026-11-22T14:00:00Z", durationMinutes: 90, status: "deleted" },
  ] }];
  const byDay = calendarEntries(sessions, monitors, new Set(["running"]), now);
  assert.deepEqual(byDay.get("2026-11-20").map(e => [e.key, e.state]), [["done", "ended"], ["running", "live"], ["later", "upcoming"]]);
  const zoom = byDay.get("2026-11-21");
  assert.equal(zoom.length, 1);
  assert.equal(zoom[0].source, "zoom");
  assert.equal(zoom[0].title, "Zoom topic");
  assert.equal(zoom[0].endsAt, "2026-11-21T15:30:00.000Z");
  assert.equal(byDay.has("2026-11-22"), false);
  assert.equal(nextEntry(byDay, now).key, "later");

  // Without Zoom's live list, a class in its time window is "in progress", never claimed live.
  assert.equal(calendarEntries([session({})], null, new Set(), now).get("2026-11-20")[0].state, "now");
});

test("live numbers count expected students who joined, and Zoom's connected count when known", () => {
  const rows = [row(true, true, 1), row(true, true), row(true, false), row(true, false), row(false, true, 1)];
  assert.deepEqual(liveNumbers(rows), { expected: 4, joined: 2, connectedNow: 2, percent: 50 });
  assert.equal(liveNumbers(rows, 15).connectedNow, 15);
  assert.equal(liveNumbers([]).percent, null);
  assert.equal(joinedLabel({ expected: 24, joined: 18 }), "18 of 24 joined · 75%");
  assert.equal(joinedLabel({ expected: 0, joined: 0 }), "No students expected");
});

test("times read naturally", () => {
  const now = Date.parse("2026-11-20T11:00:00Z");
  assert.equal(startsInLabel("2026-11-20T12:20:00Z", now), "starts in 1 h 20 min");
  assert.equal(startsInLabel("2026-11-20T11:00:30Z", now), "starting now");
  assert.equal(startsInLabel("2026-11-23T11:00:00Z", now), "starts in 3 days");
  assert.equal(startedAgoLabel("2026-11-20T10:35:00Z", now), "started 25 min ago");
  assert.equal(timeRange({ startsAt: "2026-11-20T10:30:00Z", endsAt: "2026-11-20T12:00:00Z", timeZone: "Asia/Colombo" }), "16:00–17:30");
  assert.equal(timeRange({ startsAt: "2026-11-20T10:30:00Z", endsAt: null, timeZone: "Asia/Colombo" }), "from 16:00");
  assert.equal(relativeDayName("2026-11-21", "2026-11-20"), "Tomorrow");
  assert.equal(relativeDayName("2026-11-19", "2026-11-20"), "Yesterday");
  assert.equal(relativeDayName("2026-11-24", "2026-11-20"), "Tuesday");
});

test("home links leave out what would only repeat today", () => {
  assert.equal(homeHref({ day: "2026-11-20", month: "2026-11" }, "2026-11-20"), "/staff");
  assert.equal(homeHref({ day: "2026-11-21", month: "2026-11" }, "2026-11-20"), "/staff?day=2026-11-21");
  assert.equal(homeHref({ day: "2026-11-20", month: "2026-12" }, "2026-11-20"), "/staff?month=2026-12");
});
