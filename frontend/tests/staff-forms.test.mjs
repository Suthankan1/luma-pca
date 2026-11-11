import assert from "node:assert/strict";
import test from "node:test";
import { canManageStaff, grantableRoles, parseAssignForm, parseRoles, parseStaffForm, academyToday, formatDate, isAcademyManager, parseBatchForm, parseCourseForm, parseEnrollForm, parseStudentForm, problemMessage, studentListHref, submittedValues, uuidOrNull } from "../src/lib/staff-forms.ts";

const form = fields => {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
};
const id = "6f2d3c1e-0000-4000-8000-000000000001";

test("only owners and administrators are academy managers", () => {
  assert.equal(isAcademyManager(["ACADEMY_OWNER"]), true);
  assert.equal(isAcademyManager(["INSTRUCTOR", "ACADEMY_ADMIN"]), true);
  assert.equal(isAcademyManager(["INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST", "STUDENT"]), false);
  assert.equal(isAcademyManager(["academy_admin"]), false);
});

test("ids from hidden fields must be lower-case UUIDs before they reach an API path", () => {
  assert.equal(uuidOrNull(id), id);
  for (const bad of [null, "", "../../auth/logout", `${id}/x`, id.toUpperCase(), "not-a-uuid"]) {
    assert.equal(uuidOrNull(bad), null, String(bad));
  }
});

test("course forms apply the API's rules first", () => {
  assert.deepEqual(parseCourseForm(form({ code: " phy-2027 ", title: " A/L Physics " })), { code: "phy-2027", title: "A/L Physics" });
  assert.ok("error" in parseCourseForm(form({ code: "has space", title: "T" })));
  assert.ok("error" in parseCourseForm(form({ code: "-lead", title: "T" })));
  assert.ok("error" in parseCourseForm(form({ code: "X".repeat(61), title: "T" })));
  assert.ok("error" in parseCourseForm(form({ code: "OK", title: "  " })));
  assert.ok("error" in parseCourseForm(form({ code: "OK", title: "T".repeat(201) })));
});

test("batch forms need a course, valid dates and an end on or after the start", () => {
  const good = { courseId: id, code: "MON", name: "Monday", startsOn: "2027-01-10", endsOn: "" };
  assert.deepEqual(parseBatchForm(form(good)), { courseId: id, code: "MON", name: "Monday", startsOn: "2027-01-10", endsOn: null });
  assert.equal(parseBatchForm(form({ ...good, endsOn: "2027-01-10" })).endsOn, "2027-01-10", "a one-day batch is fine");
  assert.ok("error" in parseBatchForm(form({ ...good, courseId: "x" })));
  assert.ok("error" in parseBatchForm(form({ ...good, startsOn: "" })));
  assert.ok("error" in parseBatchForm(form({ ...good, endsOn: "2027-01-09" })));
  assert.ok("error" in parseBatchForm(form({ ...good, endsOn: "soon" })));
});

test("API refusals become clear sentences without leaking generic framework text", () => {
  assert.equal(problemMessage(409, "A course with this code already exists"), "A course with this code already exists");
  assert.match(problemMessage(409, "The record was changed by someone else; reload and retry"), /Someone else changed/);
  assert.match(problemMessage(403), /owners and administrators/);
  assert.match(problemMessage(404), /no longer exists/);
  assert.match(problemMessage(400, "Invalid request content."), /not valid/);
  assert.match(problemMessage(400, "Failed to read request"), /not valid/);
  assert.match(problemMessage(500, "NullPointerException at line 4"), /couldn’t save/, "server errors never echo details");
  assert.match(problemMessage(503), /couldn’t save/);
});

test("dates are shown without time-zone shifts", () => {
  assert.equal(formatDate("2027-01-10"), "10 Jan 2027");
  assert.equal(formatDate("2027-12-31"), "31 Dec 2027");
  assert.equal(formatDate(null), "");
});

test("a refused submission hands back what was typed, capped, so the form keeps it", () => {
  const data = form({ code: "mon", name: "N".repeat(400) });
  assert.deepEqual(submittedValues(data, ["code", "missing"]), { code: "mon", missing: "" });
  assert.equal(submittedValues(data, ["name"]).name.length, 300);
});

test("student forms apply the API's rules first", () => {
  assert.deepEqual(parseStudentForm(form({ studentNumber: " pca-001 ", fullName: " Kavindi Perera ", email: " k@example.invalid " })),
    { studentNumber: "pca-001", fullName: "Kavindi Perera", email: "k@example.invalid" });
  assert.ok("error" in parseStudentForm(form({ studentNumber: "PCA 001", fullName: "N", email: "k@example.invalid" })));
  assert.ok("error" in parseStudentForm(form({ studentNumber: "PCA-1", fullName: " ", email: "k@example.invalid" })));
  for (const email of ["", "no-at", "a@b", "two@@example.invalid", `${"x".repeat(250)}@example.invalid`]) {
    assert.ok("error" in parseStudentForm(form({ studentNumber: "PCA-1", fullName: "N", email })), email);
  }
});

test("enrolments need a batch id and a date", () => {
  assert.deepEqual(parseEnrollForm(form({ batchId: id, enrolledOn: "2027-01-10" })), { batchId: id, enrolledOn: "2027-01-10" });
  assert.ok("error" in parseEnrollForm(form({ batchId: "x", enrolledOn: "2027-01-10" })));
  assert.ok("error" in parseEnrollForm(form({ batchId: id, enrolledOn: "" })));
});

test("the academy's today is the Colombo calendar date", () => {
  assert.equal(academyToday(new Date("2027-03-01T20:00:00Z")), "2027-03-02");
  assert.equal(academyToday(new Date("2027-03-01T08:00:00Z")), "2027-03-01");
});

test("student list links keep the filter and the paging cursor", () => {
  assert.equal(studentListHref({}), "/staff/students");
  assert.equal(studentListHref({ status: "active" }), "/staff/students");
  assert.equal(studentListHref({ status: "all", after: "PCA-050" }), "/staff/students?status=all&after=PCA-050");
  assert.equal(studentListHref({ status: "inactive" }), "/staff/students?status=inactive");
});

test("only owners may grant owner or administrator", () => {
  assert.deepEqual(grantableRoles(["ACADEMY_ADMIN"]), ["INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST"]);
  assert.deepEqual(grantableRoles(["ACADEMY_OWNER"]), ["ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST"]);
});

test("nobody manages themselves, and administrators never manage owners or administrators", () => {
  const admin = { accountId: "me", roles: ["ACADEMY_ADMIN"] };
  const owner = { accountId: "boss", roles: ["ACADEMY_OWNER"] };
  assert.equal(canManageStaff(admin, { id: "me", roles: ["ACADEMY_ADMIN"] }), false);
  assert.equal(canManageStaff(admin, { id: "t", roles: ["INSTRUCTOR"] }), true);
  assert.equal(canManageStaff(admin, { id: "o", roles: ["ACADEMY_OWNER"] }), false);
  assert.equal(canManageStaff(admin, { id: "a", roles: ["INSTRUCTOR", "ACADEMY_ADMIN"] }), false);
  assert.equal(canManageStaff(owner, { id: "a", roles: ["ACADEMY_ADMIN"] }), true);
  assert.equal(canManageStaff({ accountId: "i", roles: ["INSTRUCTOR"] }, { id: "t", roles: ["INSTRUCTOR"] }), false);
});

test("staff and assignment forms check roles and emails", () => {
  const data = new FormData();
  data.set("email", " teacher@example.invalid ");
  data.append("roles", "INSTRUCTOR");
  data.append("roles", "ANALYST");
  assert.deepEqual(parseStaffForm(data), { email: "teacher@example.invalid", roles: ["INSTRUCTOR", "ANALYST"] });
  const none = new FormData();
  assert.ok("error" in parseRoles(none));
  const bad = new FormData();
  bad.append("roles", "STUDENT");
  assert.ok("error" in parseRoles(bad), "students are not staff");
  const twice = new FormData();
  twice.append("roles", "ANALYST");
  twice.append("roles", "ANALYST");
  assert.ok("error" in parseRoles(twice));
  assert.deepEqual(parseAssignForm(form({ email: "t@example.invalid", role: "ATTENDANCE_OFFICER" })), { email: "t@example.invalid", role: "ATTENDANCE_OFFICER" });
  assert.ok("error" in parseAssignForm(form({ email: "t@example.invalid", role: "ANALYST" })));
  assert.ok("error" in parseAssignForm(form({ email: "nope", role: "INSTRUCTOR" })));
});

test("roles are listed senior first whatever order the API returns", async () => {
  const { roleList } = await import("../src/lib/staff-forms.ts");
  assert.equal(roleList(["ATTENDANCE_OFFICER", "INSTRUCTOR"]), "Instructor, Attendance officer");
  assert.equal(roleList(["ANALYST", "ACADEMY_OWNER"]), "Owner, Analyst");
});
