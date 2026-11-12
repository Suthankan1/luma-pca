import assert from "node:assert/strict";
import test from "node:test";
import { activityHref, actorName, describeActivity } from "../src/lib/activity.ts";

const event = (action, details = {}, extra = {}) => ({ id: "1", occurredAt: "2027-01-01T00:00:00Z", action, actorAccountId: "a",
  actorEmail: "owner@example.invalid", targetType: "ACCOUNT", targetId: "t", targetLabel: "teacher@example.invalid", details, ...extra });

test("staff actions read as sentences with role names", () => {
  assert.deepEqual(describeActivity(event("STAFF_ACCOUNT_CREATED", { roles: ["INSTRUCTOR", "ATTENDANCE_OFFICER"] })),
    { summary: "Added staff member teacher@example.invalid", detail: "Instructor, Attendance officer" });
  assert.deepEqual(describeActivity(event("STAFF_ROLES_CHANGED", { before: ["INSTRUCTOR"], after: ["ACADEMY_ADMIN"] })),
    { summary: "Changed the roles of teacher@example.invalid", detail: "Instructor → Administrator" });
  assert.equal(describeActivity(event("STAFF_ACCOUNT_ENABLED", { newPasswordRequired: "true" })).detail, "A new password is required");
  assert.equal(describeActivity(event("INVITATION_ISSUED", { delivery: "EMAIL", bulk: "true" })).detail, "Emailed (bulk)");
});

test("student, invitation and Zoom actions", () => {
  assert.deepEqual(describeActivity(event("STUDENTS_IMPORTED", { students: "600", enrolled: "600", enrolledOn: "2027-01-10" },
    { targetType: "BATCH", targetLabel: "MON" })), { summary: "Imported 600 students", detail: "Enrolled 600 in MON from 2027-01-10" });
  assert.deepEqual(describeActivity(event("INVITATIONS_QUEUED", { queued: "1", resend: "false" }, { targetType: "ACADEMY", targetLabel: null })),
    { summary: "Queued 1 invitation email", detail: "Every active student" });
  assert.deepEqual(describeActivity(event("ZOOM_LINKS_REVISED", { unlinked: "1", moved: "0", linked: "2" },
    { targetType: "CLASS_SESSION", targetLabel: "Waves" })), { summary: "Zoom links re-checked after “Waves” changed", detail: "1 back to review, 2 linked" });
  assert.equal(describeActivity(event("ZOOM_OCCURRENCE_MARKED_NOT_CLASS", { reason: "Rehearsal" }, { targetLabel: null })).detail, "Reason: Rehearsal");
});

test("unknown actions, missing targets and actors fall back safely", () => {
  assert.deepEqual(describeActivity(event("SOMETHING_NEW")), { summary: "Something new" });
  assert.equal(describeActivity(event("STAFF_ACCOUNT_DISABLED", {}, { targetLabel: null })).summary, "Disabled an account");
  assert.equal(actorName(event("X", {}, { actorEmail: null, actorAccountId: null })), "The system");
  assert.equal(actorName(event("X", {}, { actorEmail: null })), "A removed account");
});

test("filter links", () => {
  assert.equal(activityHref({}), "/staff/activity");
  assert.equal(activityHref({ category: "staff", before: "123_abc" }), "/staff/activity?category=staff&before=123_abc");
  assert.equal(activityHref({ target: "t1" }), "/staff/activity?target=t1");
});

test("timetable changes, in the class's own time zone", () => {
  const cls = (action, details) => event(action, details, { targetType: "CLASS_SESSION", targetLabel: "Waves" });
  assert.deepEqual(describeActivity(cls("CLASS_SCHEDULED", { startsAt: "2027-03-01T12:30:00Z", endsAt: "2027-03-01T14:30:00Z",
    timeZone: "Asia/Colombo" })), { summary: "Scheduled “Waves”", detail: "Mon 1 Mar 2027 · 18:00–20:00" });
  assert.deepEqual(describeActivity(cls("CLASS_UPDATED", { changed: ["batches", "time", "title"], startsAtBefore: "2027-03-01T12:30:00Z",
    startsAt: "2027-03-02T12:30:00Z", endsAt: "2027-03-02T14:30:00Z", timeZone: "Asia/Colombo" })),
    { summary: "Changed “Waves”", detail: "moved from 1 Mar 2027 18:00 to Tue 2 Mar 2027 · 18:00–20:00; changed batches, title" });
  assert.deepEqual(describeActivity(cls("CLASS_UPDATED", { changed: ["webinar"] })), { summary: "Changed “Waves”", detail: "changed Zoom webinar" });
  assert.equal(describeActivity(cls("CLASS_CANCELLED", { changed: [] })).summary, "Cancelled “Waves”");
  assert.equal(describeActivity({ ...cls("CLASS_RESTORED", {}), targetLabel: null }).summary, "Restored a class");
  assert.deepEqual(describeActivity(event("CLASS_SERIES_CREATED", { classes: "9", weekdays: ["MONDAY", "THURSDAY"], firstDate: "2027-03-01",
    lastDate: "2027-03-29" }, { targetType: "CLASS_SERIES", targetLabel: null })),
    { summary: "Scheduled a weekly series of 9 classes", detail: "Every Monday and Thursday, 1 Mar 2027 – 29 Mar 2027" });
  assert.deepEqual(describeActivity(event("CLASS_SERIES_CANCELLED", { fromDate: "2027-03-22", cancelled: "1" },
    { targetType: "CLASS_SERIES", targetLabel: null })), { summary: "Cancelled 1 class of a weekly series", detail: "From 22 Mar 2027" });
});
