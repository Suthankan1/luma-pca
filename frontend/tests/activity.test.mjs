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
