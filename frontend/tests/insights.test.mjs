import assert from "node:assert/strict";
import test from "node:test";
import { dateOrNull, formatDuration, gaps, level, presets, timelineSegments, withParams } from "../src/lib/insights.ts";

test("durations read as hours and minutes", () => {
  assert.equal(formatDuration(0), "0 min");
  assert.equal(formatDuration(1500), "25 min");
  assert.equal(formatDuration(4200), "1 h 10 min");
  assert.equal(formatDuration(7200), "2 h");
  assert.equal(formatDuration(-5), "0 min");
});

test("bands follow the academy's rule", () => {
  assert.equal(level(80, 75), "good");
  assert.equal(level(60, 75), "warn");
  assert.equal(level(40, 75), "bad");
  assert.equal(level(0, 75, false), "none");
  assert.equal(level(75, null), "good");
});

const start = "2026-11-09T03:30:00.000Z";
const at = minute => new Date(Date.parse(start) + minute * 60_000).toISOString();
const visits = [[0, 25], [35, 60], [70, 90]].map(([a, b]) => ({ joinedAt: at(a), leftAt: at(b), open: false, secondsInClass: (b - a) * 60 }));

test("the Academy's example: three connections on the class bar and the time away between them", () => {
  const segments = timelineSegments(start, at(120), visits);
  assert.deepEqual(segments.map(s => [Math.round(s.left), Math.round(s.width)]), [[0, 21], [29, 21], [58, 17]]);
  assert.deepEqual(gaps(visits), [600, 600]);
});

test("overlapping connections are not counted as time away", () => {
  const overlapping = [...visits, { joinedAt: at(50), leftAt: at(55), open: false, secondsInClass: 300 }];
  assert.deepEqual(gaps(overlapping), [600, 0, 600]);
});

test("connections outside the class are clipped to the bar", () => {
  const [early] = timelineSegments(start, at(120), [{ joinedAt: at(-10), leftAt: at(10), open: true, secondsInClass: 600 }]);
  assert.equal(early.left, 0);
  assert.ok(Math.abs(early.width - 100 / 12) < 0.01);
  assert.equal(early.open, true);
});

test("dates, presets and links", () => {
  assert.equal(dateOrNull("2026-11-18"), "2026-11-18");
  assert.equal(dateOrNull("2026-13-45"), null);
  assert.equal(dateOrNull(["2026-11-18"]), null);
  const [week, month30, thisMonth] = presets("2026-11-18");
  assert.deepEqual([week.from, month30.from, thisMonth.from], ["2026-11-12", "2026-10-20", "2026-11-01"]);
  assert.equal(withParams("/staff/dashboard", { from: "2026-11-01", course: null, view: "grid" }), "/staff/dashboard?from=2026-11-01&view=grid");
});
