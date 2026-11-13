import assert from "node:assert/strict";
import test from "node:test";
import { canManageStaff, grantableRoles, parseAssignForm, parseRoles, parseStaffForm, academyToday, formatDate, isAcademyManager, parseBatchForm, parseCourseForm, parseEnrollForm, parseStudentForm, problemMessage, studentListHref, submittedValues, uuidOrNull } from "../src/lib/staff-forms.ts";
import * as forms from "../src/lib/staff-forms.ts";

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

test("class times convert between the academy zone and UTC", () => {
  assert.equal(forms.zonedToInstant("2027-01-11", "18:00", "Asia/Colombo"), "2027-01-11T12:30:00.000Z");
  assert.equal(forms.zonedToInstant("2027-01-11", "02:00", "Asia/Colombo"), "2027-01-10T20:30:00.000Z");
  // Zones with daylight saving use the offset in force on that date.
  assert.equal(forms.zonedToInstant("2027-07-01", "09:00", "Europe/London"), "2027-07-01T08:00:00.000Z");
  assert.equal(forms.zonedToInstant("2027-01-01", "09:00", "Europe/London"), "2027-01-01T09:00:00.000Z");
  assert.deepEqual(forms.instantParts("2027-01-10T20:30:00Z", "Asia/Colombo"), { date: "2027-01-11", time: "02:00" });
  assert.equal(forms.formatClassTime("2027-01-11T12:30:00Z", "2027-01-11T14:30:00Z", "Asia/Colombo"), "Mon 11 Jan 2027 · 18:00–20:00");
});

test("the class list shows a fortnight from a valid date, else from today", () => {
  const w = forms.sessionWindow("2027-01-10", "2026-11-11");
  assert.deepEqual([w.from, w.lastDay, w.previous, w.next], ["2027-01-10", "2027-01-23", "2026-12-27", "2027-01-24"]);
  assert.equal(w.fromInstant, "2027-01-09T18:30:00.000Z");
  assert.equal(forms.sessionWindow("not-a-date", "2026-11-11").from, "2026-11-11");
  assert.equal(forms.sessionWindow("2027-02-30", "2026-11-11").from, "2026-11-11");
  assert.equal(forms.addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(forms.sessionListHref({ from: "2027-01-10", batch: null }), "/staff/sessions?from=2027-01-10");
});

test("the class form applies the API's rules first", () => {
  const batch = "00000000-0000-4000-8000-000000000001";
  const form = (fields, batches = [batch]) => {
    const data = new FormData();
    for (const [k, v] of Object.entries({ title: "Waves", date: "2027-01-11", startTime: "18:00", endTime: "20:00", ...fields })) data.set(k, v);
    for (const b of batches) data.append("batchIds", b);
    return data;
  };
  assert.deepEqual(forms.parseSessionForm(form({ zoomWebinarId: "812 3456-7890" })), {
    title: "Waves", startsAt: "2027-01-11T12:30:00.000Z", endsAt: "2027-01-11T14:30:00.000Z", timeZone: "Asia/Colombo",
    batchIds: [batch], zoomWebinarId: "81234567890" });
  assert.equal(forms.parseSessionForm(form({})).zoomWebinarId, null);
  assert.match(forms.parseSessionForm(form({ endTime: "18:00" })).error, /end after it starts/);
  assert.match(forms.parseSessionForm(form({ startTime: "06:00", endTime: "18:30" })).error, /12 hours/);
  assert.match(forms.parseSessionForm(form({}, [])).error, /at least one batch/);
  assert.match(forms.parseSessionForm(form({}, [batch, batch])).error, /from the list/);
  assert.match(forms.parseSessionForm(form({}, ["../x"])).error, /from the list/);
  assert.match(forms.parseSessionForm(form({ zoomWebinarId: "12345" })).error, /webinar ID/);
  assert.match(forms.parseSessionForm(form({ timeZone: "+05:30" })).error, /not valid/);
  assert.match(forms.parseSessionForm(form({ startTime: "6pm" })).error, /such as 18:00/);
  assert.equal(forms.formatWebinarId("81234567890"), "812 3456 7890");
});

test("Zoom review: reasons, occurrence ids, states and class candidates", () => {
  const reasonForm = value => { const f = new FormData(); f.set("reason", value); return f; };
  assert.equal(forms.parseReason(reasonForm("  Test webinar  ")), "Test webinar");
  assert.match(forms.parseReason(reasonForm("no")).error, /3 to 300/);
  assert.match(forms.parseReason(reasonForm("x".repeat(301))).error, /3 to 300/);
  assert.equal(forms.occurrenceIdOrNull("4444AAAiAAAAAiAiAiiAii=="), "4444AAAiAAAAAiAiAiiAii==");
  assert.equal(forms.occurrenceIdOrNull("ab/cd+ef"), "ab/cd+ef");
  assert.equal(forms.occurrenceIdOrNull("has space"), null);
  assert.equal(forms.occurrenceIdOrNull("x".repeat(101)), null);
  assert.equal(forms.formatInstant("2027-01-13T12:32:00Z"), "13 Jan 2027 18:02");

  const occurrence = { occurrenceUuid: "o1", webinarId: "81234567890", firstEventAt: "2027-01-13T12:40:00Z", lastEventAt: "2027-01-13T14:50:00Z",
    eventCount: 4, classSessionId: null, linkSource: null, linkedAt: null };
  assert.equal(forms.occurrenceState(occurrence), "Needs review");
  assert.equal(forms.occurrenceState({ ...occurrence, linkSource: "MANUAL" }), "Marked not a class");
  assert.equal(forms.occurrenceState({ ...occurrence, linkSource: "AUTOMATIC", classSessionId: "s" }), "Linked automatically");
  assert.equal(forms.occurrenceState({ ...occurrence, linkSource: "MANUAL", classSessionId: "s" }), "Linked by an administrator");

  const session = (id, startsAt, extra = {}) => ({ id, title: id, startsAt, endsAt: new Date(Date.parse(startsAt) + 3600_000).toISOString(),
    timeZone: "Asia/Colombo", status: "SCHEDULED", zoomWebinarId: null, ...extra });
  const candidates = forms.candidateClasses(occurrence, [
    session("near", "2027-01-13T12:30:00Z"),
    session("webinar", "2027-01-13T20:00:00Z", { zoomWebinarId: "81234567890" }),
    session("cancelled", "2027-01-13T12:30:00Z", { status: "CANCELLED" }),
    session("far", "2027-01-15T12:30:00Z"),
  ]);
  assert.deepEqual(candidates.map(c => c.id), ["webinar", "near"]);
  assert.match(candidates[0].label, /· webinar · same webinar$/);

  assert.equal(forms.candidateWindow([]), null);
  assert.deepEqual(forms.candidateWindow([occurrence]), { from: "2027-01-12T12:40:00.000Z", to: "2027-01-14T12:40:00.000Z" });
  const wide = forms.candidateWindow([occurrence, { ...occurrence, firstEventAt: "2026-01-01T00:00:00Z" }]);
  assert.equal(Date.parse(wide.to) - Date.parse(wide.from), 92 * 86_400_000);
});

test("durations are whole minutes, with hours when there are any", () => {
  assert.equal(forms.formatDuration(0), "0 min");
  assert.equal(forms.formatDuration(59), "0 min");
  assert.equal(forms.formatDuration(2700), "45 min");
  assert.equal(forms.formatDuration(3900), "1 h 05 min");
  assert.equal(forms.formatDuration(-5), "0 min");
});

test("the attendance rule form accepts whole numbers in range and a description", () => {
  const form = (percent, late, description) => {
    const f = new FormData();
    f.set("minConnectedPercent", percent); f.set("lateAfterMinutes", late); f.set("description", description);
    return f;
  };
  assert.deepEqual(forms.parsePolicyForm(form("75", "10", "Approved 1 Nov")), { minConnectedPercent: 75, lateAfterMinutes: 10, description: "Approved 1 Nov" });
  assert.match(forms.parsePolicyForm(form("0", "10", "Approved")).error, /1 to 100/);
  assert.match(forms.parsePolicyForm(form("101", "10", "Approved")).error, /1 to 100/);
  assert.match(forms.parsePolicyForm(form("75.5", "10", "Approved")).error, /whole number/);
  assert.match(forms.parsePolicyForm(form("75", "241", "Approved")).error, /0 to 240/);
  assert.match(forms.parsePolicyForm(form("75", "10", "ok")).error, /3 to 500/);
});
