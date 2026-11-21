import assert from "node:assert/strict";
import test from "node:test";
import { compileStudentMonth, studentRate, participationFor } from "../src/lib/student-month.ts";

test("monthly student rates count completed tracked sessions and preserve students without observations", () => {
  const directory = [
    { id: "a", studentNumber: "A1", fullName: "Amaya" },
    { id: "b", studentNumber: "B2", fullName: "Bina" },
    { id: "c", studentNumber: "C3", fullName: "Chaya" },
  ];
  const session = (title, state = "FINAL") => ({ title, state, endsAt: "2026-11-01T10:00:00Z" });
  const seen = { visits: 1, connectedSeconds: 1500, outcome: "PRESENT" };
  const absent = { visits: 0, connectedSeconds: 0, outcome: "ABSENT" };
  const missing = { ...absent, outcome: null };
  const { students, classTypes } = compileStudentMonth(directory, [{
    classes: [session("Revision | Batch A | Suffix"), session("Revision | Batch A | New suffix"),
      session("Model paper"), session("No report", "NO_WEBINAR"), session("Live", "LIVE")],
    students: [
      { studentId: "a", cells: [seen, absent, seen, missing, seen] },
      { studentId: "b", cells: [null, absent, null, missing, null] },
    ],
  }], Date.parse("2026-11-21"));
  assert.equal(students.length, 3);
  assert.deepEqual(classTypes.map(type => type.label), ["Model paper", "No report", "Revision | Batch A"]);
  assert.equal(studentRate(students[0], "type:revision | batch a"), 50);
  assert.equal(Math.round(studentRate(students[0], "")), 67);
  assert.equal(studentRate(students[1], ""), 0);
  assert.equal(studentRate(students[2], ""), null);
  assert.equal(studentRate(students[0], "type:no report"), null);
  assert.deepEqual(participationFor({ state: "FINAL" }, { visits: 0, connectedSeconds: 60, outcome: "LATE" }), { joined: 1, expected: 1 });
});
