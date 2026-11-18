import assert from "node:assert/strict";
import test from "node:test";
import { formatElapsed, formatWebinarId, runStart, syncNote, upcomingOccurrences, zoomSchedule } from "../src/lib/webinars.ts";

test("Zoom's schedule is shown as Zoom gives it, never invented", () => {
  assert.equal(zoomSchedule({ scheduledStartAt: null, durationMinutes: 60, timeZone: "Asia/Colombo", recurring: false }), "No Zoom schedule");
  assert.equal(zoomSchedule({ scheduledStartAt: null, durationMinutes: 60, timeZone: null, recurring: true }), "Recurring, no fixed time in Zoom");
  assert.equal(zoomSchedule({ scheduledStartAt: "2026-11-18T12:30:00Z", durationMinutes: 60, timeZone: "Asia/Colombo", recurring: false }),
    "Wed 18 Nov 2026, 18:00 · 60 min (Asia/Colombo)");
});

test("a run starts at Zoom's actual start; a missed start falls back to the first event, said so", () => {
  assert.deepEqual(runStart({ actualStartedAt: "2026-11-18T10:30:00Z", firstSeenAt: "2026-11-18T10:29:00Z" }),
    { at: "2026-11-18T10:30:00Z", known: true });
  assert.deepEqual(runStart({ actualStartedAt: null, firstSeenAt: "2026-11-18T10:29:00Z" }), { at: "2026-11-18T10:29:00Z", known: false });
});

test("upcoming occurrences skip deleted and past ones, soonest first", () => {
  const now = Date.parse("2026-11-18T00:00:00Z");
  const list = upcomingOccurrences([
    { occurrenceId: "3", startTime: "2026-12-02T12:30:00Z", durationMinutes: 120, status: "available" },
    { occurrenceId: "1", startTime: "2026-11-10T12:30:00Z", durationMinutes: 120, status: "available" },
    { occurrenceId: "2", startTime: "2026-11-25T12:30:00Z", durationMinutes: 120, status: "available" },
    { occurrenceId: "4", startTime: "2026-11-20T12:30:00Z", durationMinutes: 120, status: "deleted" },
  ], now);
  assert.deepEqual(list.map(o => o.occurrenceId), ["2", "3"]);
});

test("a failed or old Zoom read is flagged as stale", () => {
  const now = Date.parse("2026-11-18T12:00:00Z");
  assert.equal(syncNote({ syncStatus: "FAILED", syncedAt: "2026-11-18T11:00:00Z", syncError: "x" }, now).stale, true);
  assert.equal(syncNote({ syncStatus: "OK", syncedAt: "2026-11-16T11:00:00Z", syncError: null }, now).stale, true);
  assert.equal(syncNote({ syncStatus: "OK", syncedAt: "2026-11-18T11:00:00Z", syncError: null }, now).stale, false);
});

test("ids and durations read naturally", () => {
  assert.equal(formatWebinarId("84400000101"), "844 0000 0101");
  assert.equal(formatElapsed(65 * 60), "1 h 05 min");
  assert.equal(formatElapsed(25 * 60), "25 min");
});
