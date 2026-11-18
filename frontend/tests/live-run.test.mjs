import assert from "node:assert/strict";
import test from "node:test";
import { classTimeLabel, isLiveRun } from "../src/lib/staff-forms.ts";

const start = "2026-11-18T10:30:00Z"; // 16:00 Colombo
const liveEnd = "2026-11-18T22:30:00Z"; // the 12-hour limit a live run's class ends at
const now = Date.parse("2026-11-18T11:00:00Z");

test("a live Zoom run shows no invented end time", () => {
  const run = { origin: "ZOOM_RUN", startsAt: start, endsAt: liveEnd, timeZone: "Asia/Colombo" };
  assert.equal(isLiveRun(run, now), true);
  assert.equal(classTimeLabel(run, now), "Wed 18 Nov 2026 · live since 16:00");
});

test("an ended run, or a class scheduled before, shows its real times", () => {
  const ended = { origin: "ZOOM_RUN", startsAt: start, endsAt: "2026-11-18T12:10:00Z", timeZone: "Asia/Colombo" };
  assert.equal(isLiveRun(ended, now), false);
  assert.equal(classTimeLabel(ended, now), "Wed 18 Nov 2026 · 16:00–17:40");
  const old = { origin: "LUMA", startsAt: start, endsAt: liveEnd, timeZone: "Asia/Colombo" };
  assert.equal(isLiveRun(old, now), false);
});
