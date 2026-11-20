import assert from "node:assert/strict";
import test from "node:test";
import { isReportKind, reportPeriod } from "../src/lib/report-period.ts";

test("attendance reports default to the current month and cap a ranged export at today", () => {
  const today = "2026-11-21";
  assert.equal(reportPeriod(undefined, undefined, today).from, "2026-11-01");
  assert.equal(reportPeriod("2026-09", "2026-11", today).to, today);
  assert.deepEqual(reportPeriod("2024-02", "2024-03", today).months.map(month => month.last),
    ["2024-02-29", "2024-03-31"]);
  assert.equal(reportPeriod("2026-11", "2026-10", today), null);
  assert.equal(reportPeriod("2026-12", "", today), null);
  assert.equal(reportPeriod("2025-11", "2026-11", today), null);
  assert.equal(reportPeriod("2026-2", "", today), null);
  assert.equal(isReportKind("student"), true);
  assert.equal(isReportKind("other"), false);
});
