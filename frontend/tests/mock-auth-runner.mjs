import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

let rotation = 0;
let latestRefresh = "";
const jwt = "fake-access-token.for-bff.e2e-test";
let roles = [];
let studentHasEnrolments = true;
const activatedTokens = new Set();
const usedResetTokens = new Set();

const backend = createServer(async (req, res) => {
  let encoded = "";
  for await (const chunk of req) encoded += chunk;
  const body = encoded ? JSON.parse(encoded) : {};
  const send = (status, value) => {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(value ? JSON.stringify(value) : "");
  };

  if (/^\/api\/v1\/(courses|batches|students|student-invitations|enrollments|accounts|staff-accounts|assignments|class-sessions|zoom)(\/|\?|$)/.test(req.url)) {
    return academicApi(req, body, send);
  }

  if (req.url.startsWith("/api/v1/me/")) return studentApi(req, send);

  switch (req.url) {
    case "/api/v1/auth/login":
      if (body.password !== "correct-password") return send(401);
      if (body.email === "student@example.invalid" || body.email === "new-student@example.invalid") {
        roles = ["STUDENT"];
        studentHasEnrolments = body.email === "student@example.invalid";
      } else if (body.email === "instructor@example.invalid") {
        roles = ["INSTRUCTOR"];
      } else if (body.email === "admin-unenrolled@example.invalid") {
        return send(200, {
          mfaRequired: true,
          mfaToken: "challenge-token-unenrolled",
          mfaExpiresIn: 300,
          mfaEnrolled: false,
        });
      } else if (body.email === "admin-enrolled@example.invalid") {
        return send(200, {
          mfaRequired: true,
          mfaToken: "challenge-token-enrolled",
          mfaExpiresIn: 300,
          mfaEnrolled: true,
        });
      } else {
        return send(401);
      }
      break;

    case "/api/v1/auth/mfa/enroll":
      if (body.mfaToken === "challenge-token-unenrolled") {
        return send(200, {
          secret: "JBSWY3DPEHPK3PXP",
          otpauthUri: "otpauth://totp/Physics%20Cube%20Academy:admin@example.invalid?secret=JBSWY3DPEHPK3PXP&issuer=Physics%20Cube%20Academy",
        });
      }
      return send(401);

    case "/api/v1/auth/mfa/verify":
      if (body.mfaToken === "challenge-token-unenrolled") {
        if (body.code !== "123456") return send(401);
        roles = ["ACADEMY_ADMIN"];
        latestRefresh = `fake-opaque-refresh-${++rotation}`;
        return send(200, {
          accessToken: jwt,
          tokenType: "Bearer",
          expiresIn: 300,
          refreshToken: latestRefresh,
          refreshExpiresIn: 600,
          recoveryCodes: ["rec-1111", "rec-2222", "rec-3333", "rec-4444"],
        });
      }
      if (body.mfaToken === "challenge-token-enrolled") {
        if (body.code !== "654321" && body.code !== "rec-recovery") return send(401);
        roles = ["ACADEMY_ADMIN"];
        latestRefresh = `fake-opaque-refresh-${++rotation}`;
        return send(200, {
          accessToken: jwt,
          tokenType: "Bearer",
          expiresIn: 300,
          refreshToken: latestRefresh,
          refreshExpiresIn: 600,
          recoveryCodes: null,
        });
      }
      return send(401);

    // Activation codes: one letter repeated 43 times activates once ("U" is always used up),
    // so each browser project can use its own code. Anything else is unknown.
    case "/api/v1/auth/activate":
      if (/^([A-TV-Z])\1{42}$/.test(String(body.token)) && !activatedTokens.has(body.token)) {
        if (typeof body.password !== "string" || body.password.includes("weak")) return send(422, { detail: "Password rejected" });
        activatedTokens.add(body.token);
        const invited = students.find(student => student.invitationToken === body.token);
        if (invited) invited.accountStatus = "ACTIVE";
        return send(204);
      }
      return send(400, { detail: "Invitation is invalid or has expired" });

    // Password reset: requests always look the same (reset-off@ simulates email not being set up);
    // "R" × 43 (desktop) and "S" × 43 (mobile) are single-use reset codes.
    case "/api/v1/auth/password-reset/request":
      if (String(body.email).startsWith("reset-off@")) return send(503, { detail: "Password reset by email is not available." });
      return send(202);

    case "/api/v1/auth/password-reset/confirm":
      if (/^([RS])\1{42}$/.test(String(body.token)) && !usedResetTokens.has(body.token)) {
        if (typeof body.password !== "string" || body.password.includes("weak")) return send(422, { detail: "Password rejected" });
        usedResetTokens.add(body.token);
        return send(204);
      }
      return send(400, { detail: "This reset link is invalid, already used or expired. Ask for a new one." });

    case "/api/v1/auth/refresh":
      if (body.refreshToken !== latestRefresh) return send(401);
      break;

    case "/api/v1/auth/me":
      if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
      return send(200, { accountId: SELF_ID, academyId: "test-academy", roles });

    case "/api/v1/auth/logout":
      if (body.refreshToken === latestRefresh) latestRefresh = "";
      return send(204);

    default:
      return send(404);
  }

  latestRefresh = `fake-opaque-refresh-${++rotation}`;
  return send(200, { accessToken: jwt, refreshToken: latestRefresh, expiresIn: 300, refreshExpiresIn: 600 });
});

// The signed-in student's own records: two batches (one left), and classes in the coming
// days in Sri Lanka time, one cancelled and one shared by both batches. The real API's
// per-date enrolment filtering is covered by the backend tests.
function studentApi(req, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  if (!roles.includes("STUDENT")) return send(403);
  const url = new URL(req.url, "http://mock");
  const day = offset => new Date(Date.now() + offset * 86400_000).toISOString().slice(0, 10);
  const mon = { batchId: "00000000-0000-4000-8000-0000000000d1", code: "MON", name: "Monday evening" };
  const sat = { batchId: "00000000-0000-4000-8000-0000000000d2", code: "SAT", name: "Saturday morning" };
  if (url.pathname === "/api/v1/me/enrollments") {
    return send(200, !studentHasEnrolments ? [] : [
      { id: "00000000-0000-4000-8000-0000000000e1", batchId: mon.batchId, batchCode: "MON", batchName: mon.name, enrolledOn: "2026-01-05",
        withdrawnOn: null, status: "ENROLLED" },
      { id: "00000000-0000-4000-8000-0000000000e2", batchId: sat.batchId, batchCode: "SAT", batchName: sat.name, enrolledOn: "2026-01-05",
        withdrawnOn: "2026-03-31", status: "WITHDRAWN" },
    ]);
  }
  if (url.pathname === "/api/v1/me/class-sessions") {
    const from = Date.parse(url.searchParams.get("from")), to = Date.parse(url.searchParams.get("to"));
    if (!(to > from) || to - from > 92 * 86400_000) return send(400, { detail: "to must be after from and at most 92 days later" });
    // 18:00–20:00 Sri Lanka time (UTC+05:30) is 12:30–14:30 UTC.
    const classes = !studentHasEnrolments ? [] : [
      { id: "00000000-0000-4000-8000-0000000000f1", title: "Waves and oscillations", offset: 2, status: "SCHEDULED", batches: [mon] },
      { id: "00000000-0000-4000-8000-0000000000f2", title: "Revision for both batches", offset: 4, status: "CANCELLED", batches: [mon, sat] },
      { id: "00000000-0000-4000-8000-0000000000f3", title: "Far future class", offset: 40, status: "SCHEDULED", batches: [mon] },
    ];
    return send(200, classes.map(({ offset, ...c }) => ({ ...c, startsAt: `${day(offset)}T12:30:00Z`, endsAt: `${day(offset)}T14:30:00Z`,
      timeZone: "Asia/Colombo", localDate: day(offset) })).filter(c => Date.parse(c.startsAt) >= from && Date.parse(c.startsAt) < to));
  }
  return send(404);
}

// A small in-memory stand-in for the academic API, with the same status codes and
// problem details as the real backend for the cases the staff screens handle.
const courses = [];
const batches = [];
let nextId = 1;
const uuid = () => `00000000-0000-4000-8000-${String(nextId++).padStart(12, "0")}`;
const SELF_ID = "00000000-0000-4000-8000-0000000000aa";
// The signed-in administrator, an owner, an analyst, and staff added by tests.
const staff = [
  { id: SELF_ID, email: "admin-enrolled@example.invalid", roles: ["ACADEMY_ADMIN"], status: "ACTIVE", mfaEnrolled: true, version: 0 },
  { id: "00000000-0000-4000-8000-0000000000bb", email: "owner@example.invalid", roles: ["ACADEMY_OWNER"], status: "ACTIVE", mfaEnrolled: true, version: 0 },
  { id: "00000000-0000-4000-8000-0000000000cc", email: "analyst@example.invalid", roles: ["ANALYST"], status: "ACTIVE", mfaEnrolled: false, version: 0 },
];
const assignments = [];
const ELEVATED = ["ACADEMY_OWNER", "ACADEMY_ADMIN"];
const students = [];
const enrollments = [];
const invitationLetters = "PQRSTVWXYZ";
let invitations = 0;
const emailedInvitations = [];
const staffRoles = ["INSTRUCTOR", "ATTENDANCE_OFFICER"];
const publicStudent = student => {
  const copy = { ...student };
  delete copy.invitationToken;
  return copy;
};
const classSessions = [];
// The class's date where it is taught, as the API computes it.
const localDate = (iso, zone) => new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(iso));
const sessionView = s => ({ ...s, localDate: localDate(s.startsAt, s.timeZone), batches: s.batchIds.map(id => batches.find(b => b.id === id))
  .map(b => ({ batchId: b.id, courseId: b.courseId, code: b.code, name: b.name })).sort((a, b) => a.code.localeCompare(b.code)) });
/** Returns an error response for an invalid class body, as the API's service would, or null. */
function classSessionProblem(body, existing, send) {
  const refuse = (status, value) => { send(status, value); return true; };
  const ids = body.batchIds ?? [];
  if (ids.length < 1 || ids.length > 20) return refuse(400, { detail: "batchIds must list between 1 and 20 batches" });
  if (new Set(ids).size !== ids.length) return refuse(400, { detail: "batchIds must not repeat a batch" });
  for (const id of ids) {
    const batch = batches.find(b => b.id === id);
    if (!batch) return refuse(404, { detail: "Batch not found" });
    if (!batch.active && !existing.includes(id)) return refuse(409, { detail: "An inactive batch cannot be added to a class" });
  }
  const length = Date.parse(body.endsAt) - Date.parse(body.startsAt);
  if (!(length > 0) || length > 12 * 3600_000) return refuse(400, { detail: "endsAt must be after startsAt and at most 12 hours later" });
  if (body.zoomWebinarId && !/^\d{9,20}$/.test(body.zoomWebinarId.replace(/[\s-]/g, ""))) {
    return refuse(400, { detail: "zoomWebinarId must be a Zoom webinar ID such as 812 3456 7890" });
  }
  return false;
}
// Zoom review data per browser project ("1" desktop, "2" mobile): an occurrence that
// automatic linking can settle once the test schedules its class, one that needs a
// manual decision (its id contains "/", as real Zoom UUIDs can), and a failed receipt.
const occurrences = [];
const occurrenceLinks = new Map();
const receipts = [];
for (const n of ["1", "2"]) {
  const day = `2027-03-0${n}`;
  occurrences.push({ occurrenceUuid: `auto${n}AAAAAAAAAAAAAAAA==`, webinarId: `9876543210${n}`, firstEventAt: `${day}T12:35:00Z`, lastEventAt: `${day}T14:40:00Z`, eventCount: 6 });
  occurrences.push({ occurrenceUuid: `man/${n}AAAAAAAAAAAAAAA==`, webinarId: `5550001110${n}`, firstEventAt: `${day}T04:00:00Z`, lastEventAt: `${day}T05:00:00Z`, eventCount: 2 });
  receipts.push({ id: `00000000-0000-4000-8000-00000000d00${n}`, eventType: "webinar.participant_joined", eventTs: Date.parse(`${day}T12:36:00Z`),
    status: "DEAD", attempts: 1, lastError: `Payload has no occurrence UUID (case ${n})`, receivedAt: `${day}T12:36:01Z`, nextAttemptAt: null, processedAt: null });
}
const occurrenceView = o => {
  const link = occurrenceLinks.get(o.occurrenceUuid);
  return { ...o, classSessionId: link?.classSessionId ?? null, linkSource: link?.linkSource ?? null, linkedAt: link?.linkedAt ?? null };
};

function zoomApi(req, body, send, parts, query) {
  const [, , , , resource, id, action] = parts;
  if (req.method === "GET" && resource === "occurrences") {
    const decided = new URLSearchParams(query).get("decided");
    return send(200, occurrences.map(occurrenceView).filter(o => decided === null || String(o.linkSource !== null) === decided)
      .sort((a, b) => b.firstEventAt.localeCompare(a.firstEventAt)));
  }
  if (req.method === "POST" && resource === "occurrences" && id === "auto-link") {
    let linked = 0, undecided = 0;
    for (const o of occurrences.filter(o => !occurrenceLinks.has(o.occurrenceUuid))) {
      const at = Date.parse(o.firstEventAt);
      const matches = classSessions.filter(s => s.status === "SCHEDULED" && s.zoomWebinarId === o.webinarId
        && Date.parse(s.startsAt) - 3600_000 <= at && at <= Date.parse(s.endsAt) + 3600_000);
      if (matches.length === 1) {
        occurrenceLinks.set(o.occurrenceUuid, { classSessionId: matches[0].id, linkSource: "AUTOMATIC", linkedAt: new Date().toISOString() });
        linked++;
      } else undecided++;
    }
    return send(200, { linked, stillUndecided: undecided });
  }
  const reason = String(body?.reason ?? "").trim();
  if (req.method === "POST" && resource === "occurrence-links") {
    if (reason.length < 3 || reason.length > 300) return send(400, { detail: "A reason of 3 to 300 characters is required" });
    if (!occurrences.some(o => o.occurrenceUuid === body.occurrenceUuid)) return send(404, { detail: "Occurrence not found" });
    const session = body.sessionId && classSessions.find(s => s.id === body.sessionId);
    if (body.sessionId && !session) return send(404, { detail: "Class session not found" });
    if (session && session.status === "CANCELLED") return send(409, { detail: "An occurrence cannot be linked to a cancelled class" });
    occurrenceLinks.set(body.occurrenceUuid, { classSessionId: body.sessionId ?? null, linkSource: "MANUAL", linkedAt: new Date().toISOString() });
    return send(200, occurrenceView(occurrences.find(o => o.occurrenceUuid === body.occurrenceUuid)));
  }
  if (req.method === "GET" && resource === "receipts") {
    const status = new URLSearchParams(query).get("status") ?? "DEAD";
    return send(200, receipts.filter(r => r.status === status));
  }
  if (req.method === "POST" && resource === "receipts" && action === "replay") {
    if (reason.length < 3 || reason.length > 300) return send(400, { detail: "A reason of 3 to 300 characters is required" });
    const receipt = receipts.find(r => r.id === id);
    if (!receipt) return send(404, { detail: "Receipt not found" });
    if (receipt.status !== "DEAD") return send(409, { detail: "Only a dead-lettered receipt can be replayed" });
    Object.assign(receipt, { status: "RECEIVED", attempts: 0, lastError: null, nextAttemptAt: new Date().toISOString() });
    return send(204);
  }
  return send(405);
}

const enrollmentView = e => {
  const batch = batches.find(b => b.id === e.batchId);
  const student = students.find(s => s.id === e.studentId);
  return { ...e, courseId: batch.courseId, batchCode: batch.code, batchName: batch.name, studentNumber: student.studentNumber,
    studentName: student.fullName, status: e.withdrawnOn ? "WITHDRAWN" : "ENROLLED" };
};

// Same checks and messages as the real import (fewer validation details): all rows first, then all or nothing.
function importStudents(body, send) {
  const rows = body.rows;
  if (!Array.isArray(rows) || rows.length === 0 || rows.length > 1000) return send(400, { detail: "Send between 1 and 1000 rows" });
  if (!body.batchId !== !body.enrolledOn) return send(400, { detail: "batchId and enrolledOn go together" });
  const batch = body.batchId && batches.find(b => b.id === body.batchId);
  if (body.batchId && !batch) return send(404, { detail: "Not found" });
  if (batch && !batch.active) return send(409, { detail: "Students cannot be enrolled in an inactive batch" });
  const problems = [];
  const numbers = new Map(), emails = new Map();
  rows.forEach((row, i) => {
    const line = i + 1;
    const number = String(row.studentNumber ?? "").trim().toUpperCase();
    const email = String(row.email ?? "").trim().toLowerCase();
    if (!/^[A-Z0-9][A-Z0-9._-]{0,59}$/.test(number)) {
      problems.push({ row: line, field: "studentNumber", message: "Use up to 60 letters, digits, dots, dashes or underscores, starting with a letter or digit" });
    } else if (numbers.has(number)) problems.push({ row: line, field: "studentNumber", message: `Same student number as row ${numbers.get(number)}` });
    else if (students.some(s => s.studentNumber === number)) problems.push({ row: line, field: "studentNumber", message: "A student with this student number already exists" });
    else numbers.set(number, line);
    if (!String(row.fullName ?? "").trim()) problems.push({ row: line, field: "fullName", message: "A full name of up to 200 characters is required" });
    if (!/^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(email)) problems.push({ row: line, field: "email", message: "Email is not a valid address" });
    else if (emails.has(email)) problems.push({ row: line, field: "email", message: `Same email as row ${emails.get(email)}` });
    else if (students.some(s => s.email === email)) problems.push({ row: line, field: "email", message: "An account with this email already exists" });
    else emails.set(email, line);
  });
  problems.sort((a, b) => a.row - b.row || a.field.localeCompare(b.field));
  if (problems.length || body.dryRun) return send(200, { imported: false, rows: rows.length, enrolled: 0, problems });
  for (const row of rows) {
    const created = { id: uuid(), studentNumber: row.studentNumber.trim().toUpperCase(), fullName: row.fullName.trim(),
      email: row.email.trim().toLowerCase(), accountId: uuid(), accountStatus: "PENDING_ACTIVATION", active: true, version: 0 };
    students.push(created);
    if (batch) enrollments.push({ id: uuid(), batchId: batch.id, studentId: created.id, enrolledOn: body.enrolledOn, withdrawnOn: null, version: 0 });
  }
  return send(200, { imported: true, rows: rows.length, enrolled: batch ? rows.length : 0, problems: [] });
}

// Bulk invitation emails: queued here, "sent" when progress is next read (as the background sender would).
const invitedAccounts = new Set();
const queuedInvites = new Set();
let sentInvites = 0;
function studentInvitations(req, body, send, sub) {
  if (req.method === "GET" && sub === "progress") {
    const now = { waiting: queuedInvites.size, sent: sentInvites, failed: 0, cancelled: 0 };
    for (const account of queuedInvites) invitedAccounts.add(account);
    sentInvites += queuedInvites.size;
    queuedInvites.clear();
    return send(200, now);
  }
  if (req.method !== "POST" || sub) return send(404);
  if (body.batchId && !batches.some(b => b.id === body.batchId)) return send(404, { detail: "Not found" });
  const chosen = body.batchId
    ? enrollments.filter(e => e.batchId === body.batchId && !e.withdrawnOn).map(e => students.find(s => s.id === e.studentId))
    : students;
  const pending = chosen.filter(s => s.active && s.accountStatus === "PENDING_ACTIVATION");
  const counts = { pending: pending.length, queued: 0, alreadyQueued: 0, alreadyInvited: 0 };
  for (const student of pending) {
    if (queuedInvites.has(student.accountId)) counts.alreadyQueued++;
    else if (!body.resend && invitedAccounts.has(student.accountId)) counts.alreadyInvited++;
    else {
      counts.queued++;
      if (!body.dryRun) queuedInvites.add(student.accountId);
    }
  }
  return send(200, counts);
}

// Weekly series (Asia/Colombo only, UTC+05:30): the same preview, overlap and cancellation rules as the API.
function classSeries(body, send, seriesId, action) {
  if (seriesId) {
    if (action !== "cancellations" || !/^\d{4}-\d{2}-\d{2}$/.test(body.fromDate ?? "")) return send(400, { detail: "fromDate is required" });
    const series = classSessions.filter(s => s.seriesId === seriesId);
    if (series.length === 0) return send(404, { detail: "Not found" });
    const cancelled = series.filter(s => s.status === "SCHEDULED" && localDate(s.startsAt, s.timeZone) >= body.fromDate);
    for (const s of cancelled) { s.status = "CANCELLED"; s.version++; }
    return send(200, { cancelled: cancelled.length });
  }
  const days = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"];
  const skip = new Set(body.skipDates ?? []);
  const planned = [];
  for (let t = Date.parse(`${body.firstDate}T00:00:00Z`); t <= Date.parse(`${body.lastDate}T00:00:00Z`); t += 86400_000) {
    const day = new Date(t).toISOString().slice(0, 10);
    if (!body.weekdays.includes(days[new Date(t).getUTCDay()]) || skip.has(day)) continue;
    planned.push({ localDate: day, startsAt: new Date(Date.parse(`${day}T${body.startTime}:00+05:30`)).toISOString(),
      endsAt: new Date(Date.parse(`${day}T${body.endTime}:00+05:30`)).toISOString(), id: null });
  }
  if (planned.length < 1 || planned.length > 100) return send(400, { detail: `A series makes between 1 and 100 classes; it would make ${planned.length}` });
  const conflicts = [];
  for (const o of planned) {
    for (const s of classSessions) {
      if (s.status === "SCHEDULED" && s.batchIds.some(b => body.batchIds.includes(b)) && s.startsAt < o.endsAt && s.endsAt > o.startsAt) {
        conflicts.push({ localDate: o.localDate, sessionId: s.id, title: s.title, startsAt: s.startsAt, endsAt: s.endsAt });
      }
    }
  }
  if (body.dryRun || conflicts.length) return send(200, { created: false, seriesId: null, occurrences: planned, conflicts });
  const seriesIdNew = uuid();
  for (const o of planned) {
    o.id = uuid();
    classSessions.push({ id: o.id, title: String(body.title).trim(), startsAt: o.startsAt, endsAt: o.endsAt, timeZone: "Asia/Colombo",
      status: "SCHEDULED", zoomWebinarId: body.zoomWebinarId || null, batchIds: [...body.batchIds], seriesId: seriesIdNew, version: 0 });
  }
  return send(200, { created: true, seriesId: seriesIdNew, occurrences: planned, conflicts: [] });
}

function academicApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  const [path, query = ""] = req.url.split("?");
  const [, , , collection, id, sub] = path.split("/");
  const manager = roles.some(role => role === "ACADEMY_OWNER" || role === "ACADEMY_ADMIN");
  // Assigned staff may list batches; the mock has no assignments, so they see none.
  if (!manager && (collection === "batches" || collection === "class-sessions") && !id && req.method === "GET"
      && roles.some(r => staffRoles.includes(r))) return send(200, []);
  if (!manager) return send(403, { detail: "Forbidden" });
  const stale = () => send(409, { detail: "The record was changed by someone else; reload and retry" });
  const notFound = () => send(404, { detail: "Not found" });

  if (collection === "student-invitations") return studentInvitations(req, body, send, id);

  if (collection === "students") {
    if (req.method === "GET" && !id) {
      const params = new URLSearchParams(query);
      const limit = Number(params.get("limit") ?? 50);
      const after = (params.get("after") ?? "").toUpperCase();
      const active = params.get("active");
      const matching = students.filter(s => (active === null || String(s.active) === active) && s.studentNumber > after)
        .sort((a, b) => a.studentNumber.localeCompare(b.studentNumber));
      const items = matching.slice(0, limit).map(publicStudent);
      return send(200, { items, nextAfter: matching.length > limit ? items[items.length - 1].studentNumber : null });
    }
    if (req.method === "POST" && id === "import") return importStudents(body, send);
    const student = id && students.find(s => s.id === id);
    if (req.method === "POST" && !id) {
      const studentNumber = String(body.studentNumber).trim().toUpperCase();
      const email = String(body.email).trim().toLowerCase();
      if (students.some(s => s.studentNumber === studentNumber)) return send(409, { detail: "A student with this student number already exists" });
      if (students.some(s => s.email === email)) return send(409, { detail: "An account with this email already exists" });
      const created = { id: uuid(), studentNumber, fullName: String(body.fullName).trim(), email, accountId: uuid(),
        accountStatus: "PENDING_ACTIVATION", active: true, version: 0 };
      students.push(created);
      return send(201, publicStudent(created));
    }
    if (!student) return notFound();
    if (req.method === "GET" && sub === "enrollments") return send(200, enrollments.filter(e => e.studentId === id).map(enrollmentView));
    if (req.method === "GET") return send(200, publicStudent(student));
    if (req.method === "PUT") {
      if (student.version !== body.version) return stale();
      Object.assign(student, { fullName: body.fullName, active: body.active, version: student.version + 1 });
      return send(200, publicStudent(student));
    }
    return send(405);
  }

  if (collection === "staff-accounts") {
    const owner = roles.includes("ACADEMY_OWNER");
    const forbidden = detail => send(403, { detail });
    if (req.method === "GET" && !id) return send(200, staff);
    if (req.method === "POST" && !id) {
      const email = String(body.email).trim().toLowerCase();
      if (body.roles.some(r => ELEVATED.includes(r)) && !owner) return forbidden("Only an academy owner may grant the owner or administrator role");
      if (staff.some(s => s.email === email) || students.some(s => s.email === email)) return send(409, { detail: "An account with this email already exists" });
      const created = { id: uuid(), email, roles: [...body.roles].sort(), status: "PENDING_ACTIVATION", mfaEnrolled: false, version: 0 };
      staff.push(created);
      return send(201, created);
    }
    const person = staff.find(s => s.id === id);
    if (!person) return notFound();
    if (req.method === "GET") return send(200, person);
    if (person.id === SELF_ID) return forbidden(sub === "roles" ? "You cannot change your own roles" : "You cannot disable your own account");
    if (person.roles.some(r => ELEVATED.includes(r)) && !owner) return forbidden("Only an academy owner may manage an owner or administrator account");
    if (req.method === "PUT" && sub === "roles") {
      if (body.roles.some(r => ELEVATED.includes(r)) && !owner) return forbidden("Only an academy owner may grant the owner or administrator role");
      if (person.version !== body.version) return stale();
      Object.assign(person, { roles: [...body.roles].sort(), version: person.version + 1 });
      return send(200, person);
    }
    if (req.method === "POST" && sub === "disable") {
      Object.assign(person, { status: "DISABLED" });
      return send(200, person);
    }
    return send(405);
  }

  if (collection === "assignments" && req.method === "DELETE") {
    const index = assignments.findIndex(a => a.id === id);
    if (index < 0) return notFound();
    assignments.splice(index, 1);
    return send(204);
  }

  if (collection === "batches" && sub === "assignments") {
    const batch = batches.find(b => b.id === id);
    if (!batch) return notFound();
    if (req.method === "GET") return send(200, assignments.filter(a => a.batchId === id));
    const person = staff.find(s => s.email === String(body.email).trim().toLowerCase());
    if (!person) return send(404, { detail: "Staff account not found" });
    if (person.status === "DISABLED") return send(409, { detail: "A disabled account cannot be assigned" });
    if (!person.roles.includes(body.role)) return send(409, { detail: `The account does not hold the ${body.role} role` });
    if (assignments.some(a => a.batchId === id && a.accountId === person.id && a.role === body.role)) {
      return send(409, { detail: "The account is already assigned to this batch in this role" });
    }
    const created = { id: uuid(), batchId: id, courseId: batch.courseId, batchCode: batch.code, batchName: batch.name,
      accountId: person.id, email: person.email, role: body.role, createdAt: new Date().toISOString() };
    assignments.push(created);
    return send(201, created);
  }

  if (collection === "accounts" && sub === "invitations" && req.method === "POST") {
    const student = students.find(s => s.accountId === id) ?? staff.find(s => s.id === id);
    if (!student) return notFound();
    if (student.status && student.status !== "PENDING_ACTIVATION") return send(409, { detail: "Only an account pending activation can be invited" });
    if (student.accountStatus && student.accountStatus !== "PENDING_ACTIVATION") return send(409, { detail: "Only an account pending activation can be invited" });
    const token = invitationLetters[invitations++ % invitationLetters.length].repeat(43);
    student.invitationToken = token;
    const expiresAt = new Date(Date.now() + 72 * 3600 * 1000).toISOString();
    // Addresses at nomail.example.invalid simulate a deployment without email; others are "sent" and recorded.
    if (body?.delivery === "EMAIL") {
      const address = student.email;
      if (address.endsWith("@nomail.example.invalid")) return send(503, { detail: "Email is not set up for this academy yet." });
      emailedInvitations.push({ to: address, token });
      return send(201, { accountId: id, activationToken: null, activationPath: null, expiresAt, delivery: "EMAIL" });
    }
    return send(201, { accountId: id, activationToken: token, activationPath: `/activate#token=${token}`, expiresAt, delivery: "LINK" });
  }

  if (collection === "enrollments" && req.method === "PUT") {
    const enrollment = enrollments.find(e => e.id === id);
    if (!enrollment) return notFound();
    if (enrollment.version !== body.version) return stale();
    Object.assign(enrollment, { enrolledOn: body.enrolledOn, withdrawnOn: body.withdrawnOn ?? null, version: enrollment.version + 1 });
    return send(200, enrollmentView(enrollment));
  }

  if (collection === "batches" && sub === "enrollments") {
    const batch = batches.find(b => b.id === id);
    if (!batch) return notFound();
    if (req.method === "GET") return send(200, enrollments.filter(e => e.batchId === id).map(enrollmentView));
    const student = students.find(s => s.id === body.studentId);
    if (!student) return notFound();
    if (!batch.active) return send(409, { detail: "Students cannot be enrolled in an inactive batch" });
    if (!student.active) return send(409, { detail: "An inactive student cannot be enrolled" });
    if (enrollments.some(e => e.batchId === id && e.studentId === student.id)) {
      return send(409, { detail: "The student is already enrolled in this batch" });
    }
    const created = { id: uuid(), batchId: id, studentId: student.id, enrolledOn: body.enrolledOn, withdrawnOn: null, version: 0 };
    enrollments.push(created);
    return send(201, enrollmentView(created));
  }

  if (collection === "zoom") return zoomApi(req, body, send, path.split("/"), query);

  if (collection === "class-sessions") {
    if (req.method === "GET" && !id) {
      const params = new URLSearchParams(query);
      const from = Date.parse(params.get("from")), to = Date.parse(params.get("to"));
      if (!(to > from) || to - from > 92 * 86400_000) return send(400, { detail: "to must be after from and at most 92 days later" });
      const batchId = params.get("batchId");
      if (batchId && !batches.some(b => b.id === batchId)) return notFound();
      return send(200, classSessions.filter(s => Date.parse(s.startsAt) >= from && Date.parse(s.startsAt) < to && (!batchId || s.batchIds.includes(batchId)))
        .sort((a, b) => a.startsAt.localeCompare(b.startsAt)).map(sessionView));
    }
    if (req.method === "POST" && !id) {
      if (classSessionProblem(body, [], send)) return;
      const created = { id: uuid(), title: String(body.title).trim(), startsAt: body.startsAt, endsAt: body.endsAt,
        timeZone: body.timeZone ?? "Asia/Colombo", status: "SCHEDULED", zoomWebinarId: body.zoomWebinarId?.replace(/[\s-]/g, "") || null,
        batchIds: [...body.batchIds], version: 0 };
      classSessions.push(created);
      return send(201, sessionView(created));
    }
    if (id === "series" && req.method === "POST") return classSeries(body, send, sub, path.split("/")[6]);
    const session = classSessions.find(s => s.id === id);
    if (!session) return notFound();
    if (req.method === "GET" && sub === "students") {
      const day = localDate(session.startsAt, session.timeZone);
      const expected = new Map();
      for (const e of enrollments) {
        if (!session.batchIds.includes(e.batchId) || e.enrolledOn > day || (e.withdrawnOn && e.withdrawnOn <= day)) continue;
        const student = students.find(s => s.id === e.studentId);
        const entry = expected.get(student.id) ?? { studentId: student.id, studentNumber: student.studentNumber, fullName: student.fullName, batchIds: [] };
        entry.batchIds.push(e.batchId);
        expected.set(student.id, entry);
      }
      return send(200, [...expected.values()].sort((a, b) => a.studentNumber.localeCompare(b.studentNumber)));
    }
    if (req.method === "GET") return send(200, sessionView(session));
    if (req.method === "PUT") {
      if (body.status !== "SCHEDULED" && body.status !== "CANCELLED") return send(400, { detail: "status must be SCHEDULED or CANCELLED" });
      if (session.version !== body.version) return stale();
      if (classSessionProblem(body, session.batchIds, send)) return;
      Object.assign(session, { title: String(body.title).trim(), startsAt: body.startsAt, endsAt: body.endsAt, timeZone: body.timeZone,
        status: body.status, zoomWebinarId: body.zoomWebinarId?.replace(/[\s-]/g, "") || null, batchIds: [...body.batchIds], version: session.version + 1 });
      return send(200, sessionView(session));
    }
    return send(405);
  }

  const list = collection === "courses" ? courses : batches;
  if (req.method === "GET" && !id) return send(200, list);
  const found = id && list.find(item => item.id === id);
  if (req.method === "GET") return found ? send(200, found) : notFound();
  if (req.method === "POST") {
    const code = String(body.code ?? "").trim().toUpperCase();
    if (collection === "courses") {
      if (courses.some(course => course.code === code)) return send(409, { detail: "A course with this code already exists" });
      const course = { id: uuid(), code, title: body.title, active: true, version: 0 };
      courses.push(course);
      return send(201, course);
    }
    const course = courses.find(item => item.id === body.courseId);
    if (!course) return send(404, { detail: "Course not found" });
    if (!course.active) return send(409, { detail: "Batches cannot be added to an inactive course" });
    if (batches.some(batch => batch.courseId === course.id && batch.code === code)) {
      return send(409, { detail: "This course already has a batch with this code" });
    }
    const batch = { id: uuid(), courseId: course.id, code, name: body.name, startsOn: body.startsOn, endsOn: body.endsOn ?? null, active: true, version: 0 };
    batches.push(batch);
    return send(201, batch);
  }
  if (req.method === "PUT") {
    if (!found) return notFound();
    if (found.version !== body.version) return stale();
    Object.assign(found, body, { code: String(body.code).toUpperCase(), version: found.version + 1 });
    return send(200, found);
  }
  return send(405);
}

await new Promise((resolve, reject) => backend.listen(0, "127.0.0.1", resolve).once("error", reject));
const backendPort = backend.address().port;
const webPort = process.env.LUMA_PLAYWRIGHT_PORT ?? "3090";
const webOrigin = `http://127.0.0.1:${webPort}`;

const nextApp = spawn(
  process.execPath,
  ["node_modules/next/dist/bin/next", "start", "-H", "127.0.0.1", "-p", webPort],
  {
    stdio: "inherit",
    env: {
      ...process.env,
      LUMA_WEB_ORIGIN: webOrigin,
      LUMA_SESSION_KEY: randomBytes(32).toString("base64url"),
      API_INTERNAL_URL: `http://127.0.0.1:${backendPort}`,
    },
  }
);

const shutdown = () => {
  nextApp.kill();
  backend.close();
  process.exit(0);
};

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
