import assert from "node:assert/strict";
import test from "node:test";
import { batchCodeFor, classNameOrNull, formatSyncedAt, syncSummary } from "../src/lib/academy.ts";

test("batch codes are derived from free-text class names and kept unique", () => {
  assert.equal(batchCodeFor("A/L Physics 2027", []), "A-L-PHYSICS-2027");
  assert.equal(batchCodeFor("  Revision (Theory)  ", []), "REVISION-THEORY");
  assert.equal(batchCodeFor("A/L Physics 2027", ["a-l-physics-2027", "A-L-PHYSICS-2027-2"]), "A-L-PHYSICS-2027-3");
  assert.equal(batchCodeFor("භෞතික විද්‍යාව", []), "CLASS");
  assert.ok(batchCodeFor("x".repeat(100), []).length <= 60);
  assert.match(batchCodeFor("x".repeat(100), ["X".repeat(54)]), /^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/);
});

test("class names from hidden fields are checked", () => {
  assert.equal(classNameOrNull("  Physics  "), "Physics");
  assert.equal(classNameOrNull("   "), null);
  assert.equal(classNameOrNull("x".repeat(201)), null);
  assert.equal(classNameOrNull(null), null);
});

test("sync results read as one sentence", () => {
  const base = { withoutName: 0, withoutEmail: 0, withdrawnInLuma: [], inactiveInLuma: [], invalidIds: [], notInAcademyClass: [] };
  assert.equal(syncSummary({ ...base, academyStudents: 3, created: 3, enrolled: 3, alreadyEnrolled: 0 }),
    "Synced: 3 students in the Academy class · 3 new to LUMA · 3 enrolled now.");
  assert.equal(syncSummary({ ...base, academyStudents: 1, created: 0, enrolled: 0, alreadyEnrolled: 1 }),
    "Synced: 1 student in the Academy class · 0 enrolled now · 1 already enrolled.");
});

test("sync times are shown in the Academy's time zone", () => {
  assert.equal(formatSyncedAt("2026-11-18T09:46:00Z"), "18 Nov 2026, 15:16");
  assert.equal(formatSyncedAt(null), "never");
  assert.equal(formatSyncedAt("not a date"), "never");
});
