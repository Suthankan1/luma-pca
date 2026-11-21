import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

let rotation = 0;
let latestRefresh = "";
// Like the API: each issued refresh token, and when it was used. A used token still works for 30 s (parallel page loads).
const refreshTokens = new Map();
const issueRefresh = () => { const token = `fake-opaque-refresh-${++rotation}`; refreshTokens.set(token, null); return token; };
const jwt = "fake-access-token.for-bff.e2e-test";
let roles = [];
let studentHasEnrolments = true;
let studentLiveEnded = false;
let studentLiveOnline = 12;
const activatedTokens = new Set();
const usedResetTokens = new Set();
let blockedMonitors;
let releaseMonitors;
let monitorsUnavailable = false;
let blockedStudentOverview;
let releaseStudentOverview;

const backend = createServer(async (req, res) => {
  let encoded = "";
  for await (const chunk of req) encoded += chunk;
  const body = encoded ? JSON.parse(encoded) : {};
  const send = (status, value) => {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(value ? JSON.stringify(value) : "");
  };
  // A CSV download, as the API's attendance exports answer.
  send.csv = (filename, text) => {
    res.writeHead(200, { "content-type": "text/csv;charset=UTF-8", "cache-control": "no-store",
      "content-disposition": `attachment; filename="${filename}"` });
    res.end(text);
  };

  const mockOnline = req.url.match(/^\/__test__\/zoom-online\/([^/]+)$/);
  if (req.method === "PUT" && mockOnline) {
    if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
    const run = runSessions.get(mockOnline[1]);
    if (run && typeof body.ended === "boolean") {
      const session = classSessions.find(row => row.id === run.classSessionId);
      run.state = body.ended ? "ENDED" : "LIVE";
      run.actualEndedAt = body.ended ? new Date().toISOString() : null;
      session.endsAt = run.actualEndedAt ?? new Date(Date.parse(run.firstSeenAt) + 12 * 3600_000).toISOString();
      return send(200, { ended: body.ended });
    }
    if (!run || run.state !== "LIVE") return send(404);
    if (!Number.isInteger(body.connectedNow) || body.connectedNow < 0) return send(400);
    run.connectedNow = body.connectedNow;
    onlineHistories.get(mockOnline[1]).push({ at: new Date().toISOString(), online: run.connectedNow });
    return send(200, { connectedNow: run.connectedNow });
  }

  if (req.method === "PUT" && req.url === "/__test__/zoom-monitor-response") {
    if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
    releaseMonitors?.();
    blockedMonitors = body.mode === "hold" ? new Promise(resolve => { releaseMonitors = resolve; }) : undefined;
    monitorsUnavailable = body.mode === "fail";
    return send(204);
  }
  if (req.method === "GET" && req.url === "/api/v1/zoom/monitors") {
    await blockedMonitors;
    if (monitorsUnavailable) return send(503);
  }
  if (req.url.startsWith("/api/v1/zoom/monitors") || /^\/api\/v1\/class-sessions\/[^/]+\/zoom-run$/.test(req.url)) {
    return monitorsApi(req, body, send);
  }
  if (req.method === "PUT" && req.url === "/__test__/student-overview-response") {
    if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
    releaseStudentOverview?.();
    blockedStudentOverview = body.mode === "hold" ? new Promise(resolve => { releaseStudentOverview = resolve; }) : undefined;
    return send(204);
  }
  if (req.method === "PUT" && req.url === "/__test__/student-live") {
    if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
    studentLiveEnded = body.ended === true;
    studentLiveOnline = body.online ?? 12;
    return send(204);
  }
  if (/^\/api\/v1\/students\/[^/]+\/attendance-overview\?/.test(req.url)) await blockedStudentOverview;
  if (/^\/api\/v1\/attendance\/(dashboard|class-totals|latest-completed|recent-completed)(\?|$)/.test(req.url) || /^\/api\/v1\/students\/[^/]+\/attendance-overview/.test(req.url)) {
    return insightsApi(req, send);
  }

  if (/^\/api\/v1\/(courses|batches|students|student-invitations|enrollments|accounts|class-sessions|zoom|audit-events|attendance|attendance-policy|attendance-records|attendance-appeals|engagement-settings)(\/|\?|$)/.test(req.url)) {
    return academicApi(req, body, send);
  }

  if (req.url.startsWith("/api/v1/me/")) return studentApi(req, send, body);
  if (req.url.startsWith("/api/v1/academy/")) return academyApi(req, body, send);

  switch (req.url) {
    case "/api/v1/auth/login":
      if (body.password !== "correct-password") return send(401);
      if (body.email === "student@example.invalid" || body.email === "new-student@example.invalid") {
        roles = ["STUDENT"];
        studentHasEnrolments = body.email === "student@example.invalid";
      } else if (body.email === "admin-enrolled@example.invalid" || body.email === "admin-unenrolled@example.invalid"
          || body.email === "owner-enrolled@example.invalid") {
        // As the API: two roles; every staff account gets STAFF and its tokens straight from email and password.
        roles = ["STAFF"];
      } else {
        return send(401);
      }
      break;

    case "/api/v1/auth/student-id-login": {
      // As the API: the Academy registration ID alone, letter case ignored.
      const id = String(body.studentId ?? "").trim().toUpperCase();
      if (id !== "PCA001") return send(401);
      roles = ["STUDENT"];
      studentHasEnrolments = true;
      break;
    }

    case "/api/v1/auth/password":
      // As the API: the current password is re-checked; success ends every session.
      if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
      if (body.currentPassword !== "correct-password") return send(400, { detail: "The current password is not correct" });
      if ([...String(body.newPassword ?? "")].length < 12) return send(422, { detail: "Password too short" });
      return send(204);

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
      {
        const usedAt = refreshTokens.get(body.refreshToken);
        if (usedAt === undefined || (usedAt !== null && Date.now() - usedAt > 30_000)) return send(401);
        if (usedAt === null) refreshTokens.set(body.refreshToken, Date.now());
      }
      break;

    case "/api/v1/auth/me":
      if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
      return send(200, { accountId: SELF_ID, academyId: "test-academy", roles });

    case "/api/v1/auth/logout":
      if (refreshTokens.has(body.refreshToken)) { refreshTokens.delete(body.refreshToken); if (body.refreshToken === latestRefresh) latestRefresh = ""; }
      return send(204);

    default:
      return send(404);
  }

  latestRefresh = issueRefresh();
  return send(200, { accessToken: jwt, refreshToken: latestRefresh, expiresIn: 300, refreshExpiresIn: 600 });
});

// The signed-in student's own records: two batches (one left), and classes in the coming
// days in Sri Lanka time, one cancelled and one shared by both batches. The real API's
// per-date enrolment filtering is covered by the backend tests.
function studentApi(req, send, body) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  if (!roles.includes("STUDENT")) return send(403);
  const url = new URL(req.url, "http://mock");
  const day = offset => new Date(Date.now() + offset * 86400_000).toISOString().slice(0, 10);
  const mon = { batchId: "00000000-0000-4000-8000-0000000000d1", code: "MON", name: "Monday evening" };
  const sat = { batchId: "00000000-0000-4000-8000-0000000000d2", code: "SAT", name: "Saturday morning" };
  if (url.pathname === "/api/v1/me/webinar-schedule") {
    const startsAt = `${day(3)}T12:30:00Z`;
    return send(200, studentHasEnrolments && Date.parse(startsAt) >= Date.parse(url.searchParams.get("from"))
      && Date.parse(startsAt) < Date.parse(url.searchParams.get("to")) ? [{
        id: "mock-zoom-slot", title: "Upcoming Zoom webinar", startsAt, durationMinutes: 60,
        timeZone: "Asia/Colombo", batchCode: mon.code, joinUrl: "https://zoom.us/j/89900000000",
      }] : []);
  }
  if (url.pathname === "/api/v1/me/class-sessions/00000000-0000-4000-8000-0000000000f4/live-attendance") {
    if (!studentHasEnrolments || studentLiveEnded) return send(404);
    const sampledAt = new Date().toISOString();
    const firstJoinAt = new Date(Date.now() - 10 * 60_000).toISOString();
    return send(200, { joined: 16, online: studentLiveOnline, seen: true, connectedSeconds: 600, firstJoinAt, sampledAt,
      onlineHistory: [{ at: firstJoinAt, online: 4 }, { at: sampledAt, online: studentLiveOnline }] });
  }
  if (url.pathname === "/api/v1/me/attendance-overview") {
    const overview = insightsOverview(INSIGHT_STUDENTS[0]);
    return send(200, studentHasEnrolments ? overview : { ...overview, enrolments: [], classes: [] });
  }
  if (url.pathname === "/api/v1/me/student") {
    return send(200, { id: "00000000-0000-4000-8000-0000000000a1", studentNumber: "PCA001", fullName: "Amaya Perera" });
  }
  if (url.pathname === "/api/v1/me/engagement-consent") {
    const settings = engagementSettings;
    const view = () => ({ enabled: !!settings?.enabled, consentText: settings?.consentText ?? null, consentVersion: settings?.consentVersion ?? 0,
      granted: myConsent && settings && myConsent.version === settings.consentVersion ? myConsent.granted : null,
      leaderboardOptIn: !!myConsent?.leaderboardOptIn, current: !!(myConsent && settings && myConsent.version === settings.consentVersion) });
    if (req.method === "GET") return send(200, view());
    if (!settings?.enabled) return send(409, { detail: "Engagement is not switched on at this academy" });
    if (body.consentVersion !== settings.consentVersion) return send(409, { detail: "The consent wording has changed; read it again" });
    myConsent = { version: body.consentVersion, granted: body.granted, leaderboardOptIn: body.leaderboardOptIn };
    return send(200, view());
  }
  const engagementPath = url.pathname.match(/^\/api\/v1\/me\/class-sessions\/([^/]+)\/(engagement|reactions|pulses\/([^/]+)\/answers)$/);
  if (engagementPath) {
    const sessionId = engagementPath[1];
    if (engagementPath[2] === "engagement") {
      return send(200, { ...mockEngagement(sessionId, true), classStartsAt: new Date(Date.now() - 20 * 60_000).toISOString(),
        classEndsAt: new Date(Date.now() + 90 * 60_000).toISOString() });
    }
    if (!engagementSettings?.enabled || !myConsent?.granted) return send(409, { detail: "Turn on engagement in your portal first" });
    if (engagementPath[2] === "reactions") {
      const counts = reactionsBySession.get(sessionId) ?? {};
      counts[body.kind] = (counts[body.kind] ?? 0) + 1;
      reactionsBySession.set(sessionId, counts);
      return send(204);
    }
    const question = (pulses.get(sessionId) ?? []).find(q => q.id === engagementPath[3]);
    if (!question || !question.open) return send(409, { detail: "This question has closed" });
    if (question.myAnswer != null) return send(409, { detail: "You have already answered this question" });
    question.myAnswer = body.option;
    question.counts[body.option]++;
    return send(204);
  }
  if (url.pathname === "/api/v1/me/attendance" && req.method === "GET") {
    return send(200, !studentHasEnrolments ? [] : [{ ...myRecord, appealStatus: appealStatusOf(myRecord.id) }]);
  }
  const appealPath = url.pathname.match(/^\/api\/v1\/me\/attendance\/([^/]+)\/appeals$/);
  if (appealPath && req.method === "POST") {
    if (appealPath[1] !== myRecord.id || !studentHasEnrolments) return send(404, { detail: "Attendance record not found" });
    const message = String(body?.message ?? "").trim();
    if (message.length < 10 || message.length > 1000) return send(400, { detail: "Explain the appeal in 10 to 1000 characters" });
    if (appealStatusOf(myRecord.id) === "OPEN") return send(409, { detail: "An appeal for this class is already waiting for a decision" });
    const appeal = { id: uuid(), recordId: myRecord.id, studentId: "00000000-0000-4000-8000-0000000000c1", studentNumber: "PCA-STUDENT",
      studentName: "Mock Student", classTitle: myRecord.classTitle, classStartsAt: myRecord.classStartsAt, timeZone: myRecord.timeZone,
      outcome: myRecord.outcome, message, status: "OPEN", createdAt: new Date().toISOString(), resolutionNote: null, resolvedAt: null };
    attendanceAppeals.push(appeal);
    return send(201, appeal);
  }
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
    // One class running now (20 minutes in), so "Take part" can be offered during a class.
    const live = !studentHasEnrolments ? [] : [{ id: "00000000-0000-4000-8000-0000000000f4", title: "Live class now", status: "SCHEDULED", batches: [mon],
      startsAt: new Date(Date.now() - 20 * 60_000).toISOString(), endsAt: new Date(Date.now() + (studentLiveEnded ? -1 : 90 * 60_000)).toISOString(), timeZone: "Asia/Colombo",
      localDate: new Date(Date.now() - 20 * 60_000).toISOString().slice(0, 10) }];
    return send(200, [...classes.map(({ offset, ...c }) => ({ ...c, startsAt: `${day(offset)}T12:30:00Z`, endsAt: `${day(offset)}T14:30:00Z`,
      timeZone: "Asia/Colombo", localDate: day(offset) })), ...live].filter(c => Date.parse(c.startsAt) >= from && Date.parse(c.startsAt) < to)
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt)).map(c => ({ ...c, joinUrl: c.status === "SCHEDULED" ? "https://zoom.us/j/81234567890" : null })));
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
// Staff accounts are managed by the operator (no staff API); invitations may still target one that is pending.
const staff = [
  { id: SELF_ID, email: "admin-enrolled@example.invalid", roles: ["STAFF"], status: "ACTIVE", version: 0 },
];
const students = [];
const enrollments = [];
const invitationLetters = "PQRSTVWXYZ";
let invitations = 0;
const emailedInvitations = [];
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
// People Zoom reported per occurrence (as stored from joined/left events), for the manual occurrences:
// the host, a student who signs in with this email (created by the test), and a guest account.
const zoomPeople = new Map();
for (const n of ["1", "2"]) {
  const day = `2027-03-0${n}`;
  zoomPeople.set(`man/${n}AAAAAAAAAAAAAAA==`, [
    { email: `teacher${n}@example.invalid`, displayName: "Teacher", host: true, visits: 1, openVisits: 0, firstSeenAt: `${day}T04:00:00Z`, lastSeenAt: `${day}T05:00:00Z` },
    { email: `zoom.student${n}@example.invalid`, displayName: `Zoom Student ${n}`, host: false, visits: 2, openVisits: 1, firstSeenAt: `${day}T04:01:00Z`, lastSeenAt: `${day}T04:30:00Z` },
    { email: `zoom.guest${n}@example.invalid`, displayName: `Guest Viewer ${n}`, host: false, visits: 1, openVisits: 0, firstSeenAt: `${day}T04:02:00Z`, lastSeenAt: `${day}T04:50:00Z` },
  ]);
}
const identityLinks = new Map();
const lookupReports = new Set();
function resolvePerson(person) {
  if (person.host) return { match: "HOST", matchSource: null, student: null };
  if (identityLinks.has(person.email)) {
    const link = identityLinks.get(person.email);
    const student = link.studentId && students.find(s => s.id === link.studentId);
    return student ? { match: "STUDENT", matchSource: "MANUAL", student } : { match: "NOT_A_STUDENT", matchSource: "MANUAL", student: null };
  }
  const student = students.find(s => s.email === person.email);
  return student ? { match: "STUDENT", matchSource: "SIGN_IN_EMAIL", student } : { match: "UNMATCHED", matchSource: null, student: null };
}

// Zoom participant reports fetched per occurrence (all seeded occurrences have ended).
const zoomReports = new Map();
// A past webinar per project (5 / 6 Mar 2026), for finalising attendance: the host and one student.
for (const n of ["1", "2"]) {
  const day = `2026-03-0${Number(n) + 4}`;
  occurrences.push({ occurrenceUuid: `past/${n}AAAAAAAAAAAAAAA==`, webinarId: `4440001110${n}`, firstEventAt: `${day}T03:35:00Z`, lastEventAt: `${day}T05:30:00Z`, eventCount: 4 });
  zoomPeople.set(`past/${n}AAAAAAAAAAAAAAA==`, [
    { email: `host.past${n}@example.invalid`, displayName: "Teacher", host: true, visits: 1, openVisits: 0, firstSeenAt: `${day}T03:30:00Z`, lastSeenAt: `${day}T05:30:00Z` },
    { email: `final.student${n}@example.invalid`, displayName: `Final Student ${n}`, host: false, visits: 1, openVisits: 0, firstSeenAt: `${day}T03:45:00Z`, lastSeenAt: `${day}T05:30:00Z` },
  ]);
}
// The academy's rule and finalised classes.
let attendancePolicy = null;
const attendanceRecords = [];
// The signed-in mock student's own final record (Waves and oscillations, last week), and appeals from any student.
const myRecord = { id: "00000000-0000-4000-8000-0000000000a1", classSessionId: "00000000-0000-4000-8000-0000000000f1", classTitle: "Waves and oscillations",
  classStartsAt: "2026-11-02T12:30:00Z", classEndsAt: "2026-11-02T14:30:00Z", timeZone: "Asia/Colombo", computedOutcome: "ABSENT", outcome: "ABSENT",
  connectedSeconds: 1200, firstJoinAt: "2026-11-02T13:50:00Z", secondsAfterStart: 4800, finalisedAt: "2026-11-02T15:00:00Z", version: 0 };
const attendanceAppeals = [];
// Engagement (006): off until an owner switches it on; the mock student's consent; reactions and pulses per class.
let engagementSettings = null;
let myConsent = null;
const pulses = new Map();
const reactionsBySession = new Map();
const mockEngagement = (sessionId, studentView) => ({ sessionId, enabled: !!engagementSettings?.enabled,
  reactions: { UNDERSTOOD: 0, CONFUSED: 0, SLOW_DOWN: 0, SPEED_UP: 0, ...(reactionsBySession.get(sessionId) ?? {}) },
  reactingStudents: Object.values(reactionsBySession.get(sessionId) ?? {}).some(n => n > 0) ? 1 : 0,
  pulses: (pulses.get(sessionId) ?? []).filter(q => !studentView || q.open), source: "PORTAL_REACTION, PORTAL_PULSE" });
const appealStatusOf = recordId => attendanceAppeals.filter(a => a.recordId === recordId).at(-1)?.status ?? null;
const finalisedClasses = new Map();
const occurrenceView = o => {
  const link = occurrenceLinks.get(o.occurrenceUuid);
  const report = zoomReports.get(o.occurrenceUuid);
  return { ...o, classSessionId: link?.classSessionId ?? null, linkSource: link?.linkSource ?? null, linkedAt: link?.linkedAt ?? null,
    ended: true, reportStatus: report ? "FETCHED" : null, reportFetchedAt: report?.fetchedAt ?? null, reportJoinSessions: report?.joinSessions ?? null };
};

// As the API's ClassLinkReviser: after a class changes, automatic links on its webinars are kept only
// while exactly that class still fits; undecided occurrences are matched again; manual decisions stay.
function reviseLinks(webinarIds) {
  for (const o of occurrences.filter(o => webinarIds.includes(o.webinarId))) {
    const link = occurrenceLinks.get(o.occurrenceUuid);
    if (link?.linkSource === "MANUAL") continue;
    const at = Date.parse(o.firstEventAt);
    const fits = classSessions.filter(s => s.status === "SCHEDULED" && s.zoomWebinarId === o.webinarId
      && Date.parse(s.startsAt) - 3600_000 <= at && at <= Date.parse(s.endsAt) + 3600_000);
    if (link && (fits.length !== 1 || fits[0].id !== link.classSessionId)) occurrenceLinks.delete(o.occurrenceUuid);
    if (!occurrenceLinks.has(o.occurrenceUuid) && fits.length === 1) {
      occurrenceLinks.set(o.occurrenceUuid, { classSessionId: fits[0].id, linkSource: "AUTOMATIC", linkedAt: new Date().toISOString() });
    }
  }
}

function zoomApi(req, body, send, parts, query) {
  const [, , , , resource, id, action] = parts;
  // Upcoming webinars from the academy's Zoom account: one weekly webinar with three dates in Feb 2027.
  const upcomingRuns = ["2027-02-01", "2027-02-08", "2027-02-15"].map((d, i) => ({ occurrenceId: `16${i}`, startsAt: `${d}T12:30:00.000Z`,
    endsAt: `${d}T14:30:00.000Z` }));
  const scheduledFor = run => classSessions.find(c => c.zoomWebinarId === "84400000001" && c.status === "SCHEDULED"
    && Math.abs(Date.parse(c.startsAt) - Date.parse(run.startsAt)) <= 30 * 60_000)?.id ?? null;
  const mockWebinars = [{ webinarId: "84400000001", topic: "A/L Physics weekly", timeZone: "Asia/Colombo", recurring: true, note: null,
    liveEvents: true, runs: upcomingRuns.map(r => ({ ...r, classId: scheduledFor(r) })) },
  { webinarId: "84400000002", topic: "Office hours", timeZone: "Asia/Colombo", recurring: true, runs: [], liveEvents: false,
    note: "A recurring webinar with no fixed time has no dates to import" }];
  if (req.method === "GET" && resource === "upcoming-webinars" && !id) {
    const q = (new URLSearchParams(query).get("q") ?? "").toLowerCase();
    return send(200, mockWebinars.filter(w => !q || w.topic.toLowerCase().includes(q) || w.webinarId.includes(q.replace(/\D/g, "") || "~"))
      .map(w => ({ webinarId: w.webinarId, topic: w.topic, nextStart: w.runs[0]?.startsAt ?? null, durationMinutes: 120,
        timeZone: w.timeZone, recurring: w.recurring, liveEvents: w.liveEvents,
        upcomingClasses: w.runs.filter(r => r.classId).length })));
  }
  if (req.method === "GET" && resource === "upcoming-webinars" && id) {
    const found = mockWebinars.find(w => w.webinarId === id);
    return found ? send(200, found) : send(404, { detail: "Zoom webinar not found" });
  }
  if (req.method === "POST" && resource === "webinar-imports") {
    if (body.webinarId !== "84400000001") return send(404, { detail: "Zoom webinar not found" });
    if (!Array.isArray(body.batchIds) || body.batchIds.length === 0) return send(400, { detail: "Choose at least one batch" });
    if (body.batchIds.some(b => !batches.some(x => x.id === b))) return send(404, { detail: "Batch not found" });
    let created = 0, existing = 0;
    for (const run of upcomingRuns.filter(r => !body.occurrenceIds?.length || body.occurrenceIds.includes(r.occurrenceId))) {
      if (scheduledFor(run)) { existing++; continue; }
      classSessions.push({ id: uuid(), title: body.title || "A/L Physics weekly", startsAt: run.startsAt, endsAt: run.endsAt,
        timeZone: "Asia/Colombo", status: "SCHEDULED", zoomWebinarId: "84400000001", batchIds: [...body.batchIds], version: 0 });
      created++;
    }
    audit("ZOOM_WEBINAR_IMPORTED", "ZOOM_WEBINAR", null, null, { webinar: "84400000001", created: String(created), alreadyScheduled: String(existing) });
    return send(201, { webinarId: "84400000001", created, alreadyScheduled: existing, classIds: [] });
  }
  if (req.method === "GET" && resource === "occurrences") {
    const decided = new URLSearchParams(query).get("decided");
    const sessionId = new URLSearchParams(query).get("sessionId");
    return send(200, occurrences.map(occurrenceView).filter(o => decided === null || String(o.linkSource !== null) === decided)
      .filter(o => !sessionId || o.classSessionId === sessionId)
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
  if (req.method === "GET" && resource === "participants" && id === "unmatched") {
    const seen = new Map();
    for (const people of zoomPeople.values()) for (const person of people) {
      if (person.host || resolvePerson(person).match !== "UNMATCHED") continue;
      const before = seen.get(person.email);
      seen.set(person.email, { email: person.email, displayName: person.displayName, occurrences: (before?.occurrences ?? 0) + 1,
        lastSeenAt: before && before.lastSeenAt > person.lastSeenAt ? before.lastSeenAt : person.lastSeenAt });
    }
    return send(200, [...seen.values()].sort((a, b) => b.lastSeenAt.localeCompare(a.lastSeenAt)));
  }
  if (req.method === "GET" && resource === "participants") {
    const uuid = new URLSearchParams(query).get("occurrence");
    const occurrence = occurrences.find(o => o.occurrenceUuid === uuid);
    if (!occurrence) return send(404, { detail: "Occurrence not found" });
    const sessionId = occurrenceLinks.get(uuid)?.classSessionId ?? null;
    const session = sessionId && classSessions.find(s => s.id === sessionId);
    const expected = new Set(session ? enrollments.filter(e => session.batchIds.includes(e.batchId) && !e.withdrawnOn).map(e => e.studentId) : []);
    const order = ["UNMATCHED", "STUDENT", "NOT_A_STUDENT", "HOST"];
    const participants = (zoomPeople.get(uuid) ?? []).map(person => {
      const { match, matchSource, student } = resolvePerson(person);
      return { email: person.email, displayName: person.displayName, visits: person.visits, openVisits: person.openVisits,
        firstSeenAt: person.firstSeenAt, lastSeenAt: person.lastSeenAt, match, matchSource, studentId: student?.id ?? null,
        studentNumber: student?.studentNumber ?? null, studentName: student?.fullName ?? null,
        expected: session && student ? expected.has(student.id) : null };
    }).sort((a, b) => order.indexOf(a.match) - order.indexOf(b.match));
    return send(200, { occurrenceUuid: uuid, classSessionId: sessionId, participants });
  }
  if (req.method === "POST" && resource === "reports") {
    const occurrence = occurrences.find(o => o.occurrenceUuid === body.occurrenceUuid);
    if (!occurrence) return send(404, { detail: "Occurrence not found" });
    const joinSessions = (zoomPeople.get(occurrence.occurrenceUuid) ?? []).reduce((n, p) => n + p.visits, 0);
    zoomReports.set(occurrence.occurrenceUuid, { fetchedAt: new Date().toISOString(), joinSessions });
    audit("ZOOM_REPORT_FETCHED", "ZOOM_OCCURRENCE", null, null, { occurrenceUuid: occurrence.occurrenceUuid, status: "FETCHED", joinSessions: String(joinSessions) });
    return send(200, { occurrenceUuid: occurrence.occurrenceUuid, status: "FETCHED", joinSessions, error: null });
  }
  if (req.method === "POST" && resource === "identity-links" && id === "removals") {
    const email = String(body.email ?? "").trim().toLowerCase();
    if (String(body.reason ?? "").trim().length < 3) return send(400, { detail: "A reason of 3 to 300 characters is required" });
    if (!identityLinks.delete(email)) return send(404, { detail: "Decision for this Zoom email not found" });
    audit("ZOOM_IDENTITY_DECISION_REMOVED", "ZOOM_IDENTITY", null, null, { email, reason: String(body.reason).trim() });
    return send(204);
  }
  if (req.method === "POST" && resource === "identity-links") {
    const email = String(body.email ?? "").trim().toLowerCase();
    if (reason.length < 3 || reason.length > 300) return send(400, { detail: "A reason of 3 to 300 characters is required" });
    if (![...zoomPeople.values()].flat().some(p => p.email === email)) return send(404, { detail: "Zoom participant not found" });
    const student = body.studentId && students.find(s => s.id === body.studentId);
    if (body.studentId && !student) return send(404, { detail: "Student not found" });
    identityLinks.set(email, { studentId: body.studentId ?? null, reason, linkedAt: new Date().toISOString() });
    audit(student ? "ZOOM_IDENTITY_LINKED" : "ZOOM_IDENTITY_MARKED_NOT_STUDENT", "ZOOM_IDENTITY", body.studentId ?? null,
      student ? student.studentNumber : null, { email, previous: "none", reason });
    return send(200, { email, studentId: body.studentId ?? null, studentNumber: student?.studentNumber ?? null,
      studentName: student?.fullName ?? null, reason, linkedAt: new Date().toISOString() });
  }
  if (resource === "webinar-lookup") {
    // As the API, for webinar 777 0001 2345: two past runs; the older one's report has the zoom.student and a guest.
    const params = new URLSearchParams(query);
    const raw = String(req.method === "GET" ? params.get("webinar") ?? "" : body.webinar ?? "").trim();
    const compact = raw.replace(/[\s-]/g, "");
    const linkId = raw.match(/^https:\/\/[^ ]*\/(?:j|w|s|wc\/join|webinar)\/(\d{9,20})(?:[/?#]|$)/)?.[1];
    const webinarId = /^\d{9,20}$/.test(compact) ? compact : linkId;
    if (!webinarId) return send(400, { detail: "Paste the webinar's join link or its ID (9 to 20 digits). Registration links (…/webinar/register/WN_…) do not contain the ID: copy the ID from the webinar's page in Zoom." });
    if (webinarId !== "77700012345") return send(404, { detail: "Webinar in the academy's Zoom account not found" });
    const runs = [{ occurrenceUuid: "lookup/oldAAAAAAAAAAAAA==", startTime: "2026-03-10T03:30:00Z" }, { occurrenceUuid: "lookup/newAAAAAAAAAAAAA==", startTime: "2026-03-17T03:30:00Z" }];
    const runView = r => ({ ...r, reported: lookupReports.has(r.occurrenceUuid), linkedSessionId: occurrenceLinks.get(r.occurrenceUuid)?.classSessionId ?? null });
    const attendance = uuid => {
      const people = [{ email: "lookup.student@example.invalid", displayName: "Lookup Student", joinSessions: 2, connectedSeconds: 4140,
          firstJoinAt: "2026-03-10T03:31:00Z", lastLeaveAt: "2026-03-10T04:40:00Z" },
        { email: "lookup.guest@example.invalid", displayName: "Lookup Guest", joinSessions: 1, connectedSeconds: 600,
          firstJoinAt: "2026-03-10T03:40:00Z", lastLeaveAt: "2026-03-10T03:50:00Z" }].map(p => {
        const { match, matchSource, student } = resolvePerson({ ...p, host: false });
        return { ...p, match, matchSource, studentId: student?.id ?? null, studentNumber: student?.studentNumber ?? null, studentName: student?.fullName ?? null };
      }).sort((a, b) => (a.match === "UNMATCHED" ? 0 : 1) - (b.match === "UNMATCHED" ? 0 : 1));
      return { webinarId, occurrenceUuid: uuid, startTime: "2026-03-10T03:31:00Z", endTime: "2026-03-10T04:40:00Z", joinSessions: 3,
        linkedSessionId: occurrenceLinks.get(uuid)?.classSessionId ?? null, attendees: people };
    };
    if (req.method === "GET" && !id) return send(200, { webinarId, topic: "Lookup revision", durationMinutes: 60, timezone: "Asia/Colombo", runs: runs.map(runView).reverse() });
    if (req.method === "GET" && id === "attendance") {
      const uuid = params.get("occurrence");
      return lookupReports.has(uuid) ? send(200, attendance(uuid)) : send(404, { detail: "Participant report not found" });
    }
    if (req.method === "POST" && id === "reports") {
      if (!runs.some(r => r.occurrenceUuid === body.occurrenceUuid)) return send(404, { detail: "Webinar run not found" });
      lookupReports.add(body.occurrenceUuid);
      return send(200, attendance(body.occurrenceUuid));
    }
    if (req.method === "POST" && id === "links") {
      if (!lookupReports.has(body.occurrenceUuid)) return send(409, { detail: "Fetch Zoom's participant report for this run first" });
      if (String(body.reason ?? "").trim().length < 3) return send(400, { detail: "A reason of 3 to 300 characters is required" });
      occurrenceLinks.set(body.occurrenceUuid, { classSessionId: body.sessionId, linkSource: "MANUAL", linkedAt: new Date().toISOString() });
      return send(200, attendance(body.occurrenceUuid));
    }
    return send(405);
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
  audit("STUDENTS_IMPORTED", batch ? "BATCH" : "ACADEMY", batch ? batch.id : null, batch ? batch.code : null,
    { students: String(rows.length), enrolled: String(batch ? rows.length : 0), ...(batch ? { enrolledOn: body.enrolledOn } : {}) });
  return send(200, { imported: true, rows: rows.length, enrolled: batch ? rows.length : 0, problems: [] });
}

// Bulk invitation emails: queued here, "sent" when progress is next read (as the background sender would).
const invitedAccounts = new Set();
const queuedInvites = new Set();
let sentInvites = 0;
// Addresses containing "bounce" fail every attempt, as an unreachable mailbox would.
const failedInvites = new Map();
function studentInvitations(req, body, send, sub) {
  if (req.method === "GET" && sub === "progress") {
    const now = { waiting: queuedInvites.size, sent: sentInvites, failed: failedInvites.size, cancelled: 0 };
    for (const account of queuedInvites) {
      const student = students.find(s => s.accountId === account);
      if (student?.email.includes("bounce")) failedInvites.set(account, new Date().toISOString());
      else { invitedAccounts.add(account); sentInvites++; }
    }
    queuedInvites.clear();
    return send(200, now);
  }
  if (req.method === "GET" && sub === "failures") {
    return send(200, [...failedInvites.keys()].map(account => students.find(s => s.accountId === account))
      .filter(s => s && s.accountStatus === "PENDING_ACTIVATION").map(s => ({ studentId: s.id, studentNumber: s.studentNumber,
        fullName: s.fullName, email: s.email, reason: "MailSendException", failedAt: failedInvites.get(s.accountId), attempts: 5 })));
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
    else if (!body.resend && invitedAccounts.has(student.accountId) && !failedInvites.has(student.accountId)) counts.alreadyInvited++;
    else {
      counts.queued++;
      if (!body.dryRun) { queuedInvites.add(student.accountId); failedInvites.delete(student.accountId); }
    }
  }
  return send(200, counts);
}

// Weekly series (Asia/Colombo only, UTC+05:30): the same preview, overlap and cancellation rules as the API.
function classSeries(body, send, seriesId, action) {
  if (seriesId && action === "changes") {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(body.fromDate ?? "")) return send(400, { detail: "fromDate is required" });
    const series = classSessions.filter(s => s.seriesId === seriesId);
    if (series.length === 0) return send(404, { detail: "Not found" });
    const affected = series.filter(s => s.status === "SCHEDULED" && localDate(s.startsAt, s.timeZone) >= body.fromDate);
    if (affected.length === 0) return send(400, { detail: "This series has no scheduled classes on or after that date" });
    const webinar = body.clearWebinar ? null : body.zoomWebinarId?.replace(/[\s-]/g, "") || undefined;
    const planned = affected.map(s => {
      const day = localDate(s.startsAt, s.timeZone);
      const startsAt = body.startTime ? new Date(Date.parse(`${day}T${body.startTime}:00+05:30`)).toISOString() : s.startsAt;
      const endsAt = body.startTime ? new Date(Date.parse(`${day}T${body.endTime}:00+05:30`)).toISOString() : s.endsAt;
      return { session: s, localDate: day, startsAt, endsAt, id: s.id };
    });
    const conflicts = [];
    for (const o of planned) {
      for (const other of classSessions) {
        if (other.status === "SCHEDULED" && !affected.includes(other) && other.batchIds.some(b => o.session.batchIds.includes(b))
          && other.startsAt < o.endsAt && other.endsAt > o.startsAt) {
          conflicts.push({ localDate: o.localDate, sessionId: other.id, title: other.title, startsAt: other.startsAt, endsAt: other.endsAt });
        }
      }
    }
    const occurrences = planned.map(o => ({ localDate: o.localDate, startsAt: o.startsAt, endsAt: o.endsAt, id: o.id }));
    if (body.dryRun || conflicts.length) return send(200, { applied: false, occurrences, conflicts });
    const webinars = new Set(affected.map(s => s.zoomWebinarId));
    for (const o of planned) {
      Object.assign(o.session, { startsAt: o.startsAt, endsAt: o.endsAt, version: o.session.version + 1,
        ...(body.title ? { title: String(body.title).trim() } : {}), ...(webinar !== undefined ? { zoomWebinarId: webinar } : {}) });
      webinars.add(o.session.zoomWebinarId);
    }
    reviseLinks([...webinars]);
    audit("CLASS_SERIES_UPDATED", "CLASS_SERIES", seriesId, null, { fromDate: body.fromDate, classes: String(affected.length),
      changed: [body.startTime && "time", body.title && "title", webinar !== undefined && "webinar"].filter(Boolean),
      ...(body.startTime ? { startTime: body.startTime, endTime: body.endTime } : {}) });
    return send(200, { applied: true, occurrences, conflicts: [] });
  }
  if (seriesId) {
    if (action !== "cancellations" || !/^\d{4}-\d{2}-\d{2}$/.test(body.fromDate ?? "")) return send(400, { detail: "fromDate is required" });
    const series = classSessions.filter(s => s.seriesId === seriesId);
    if (series.length === 0) return send(404, { detail: "Not found" });
    const cancelled = series.filter(s => s.status === "SCHEDULED" && localDate(s.startsAt, s.timeZone) >= body.fromDate);
    for (const s of cancelled) { s.status = "CANCELLED"; s.version++; }
    reviseLinks(cancelled.map(s => s.zoomWebinarId));
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
      status: "SCHEDULED", zoomWebinarId: body.zoomWebinarId?.replace(/[\s-]/g, "") || null, batchIds: [...body.batchIds], seriesId: seriesIdNew, version: 0 });
  }
  reviseLinks([body.zoomWebinarId?.replace(/[\s-]/g, "") || null]);
  return send(200, { created: true, seriesId: seriesIdNew, occurrences: planned, conflicts: [] });
}

// The activity log: appended by the routes above, read newest first with the API's filters and cursor.
const auditEvents = [];
function audit(action, targetType, targetId, targetLabel, details) {
  auditEvents.push({ id: uuid(), seq: auditEvents.length + 1, occurredAt: new Date().toISOString(), action,
    actorAccountId: SELF_ID, actorEmail: "admin-enrolled@example.invalid", targetType, targetId, targetLabel, details });
}
const auditCategories = { staff: /^(STAFF_|OWNER_)/, "sign-in": /^(INVITATIONS?_|PASSWORD_RESET_)/, students: /^STUDENTS_/, classes: /^(CLASS_|ATTENDANCE_)/, zoom: /^ZOOM_/ };
function auditApi(query, send) {
  const params = new URLSearchParams(query);
  const category = params.get("category");
  if (category && !auditCategories[category]) return send(400, { detail: "category must be one of sign-in, staff, students, zoom" });
  const limit = Number(params.get("limit") ?? 50);
  const before = Number((params.get("before") ?? "").split("_")[0] || Infinity);
  const matching = auditEvents.filter(e => (!category || auditCategories[category].test(e.action))
    && (!params.get("targetId") || e.targetId === params.get("targetId")) && e.seq < before).reverse();
  const page = matching.slice(0, limit);
  const last = page[page.length - 1];
  return send(200, { items: page.map(e => Object.fromEntries(Object.entries(e).filter(([key]) => key !== "seq"))), nextBefore: matching.length > limit ? `${last.seq}_${last.id}` : null });
}

// As the API: expected students, then matched students seen but not expected; time clipped to the class; final outcomes once finalised.
function mockAttendance(session) {
  const day = localDate(session.startsAt, session.timeZone);
  const expectedIds = new Set(enrollments.filter(e => session.batchIds.includes(e.batchId) && e.enrolledOn <= day
    && !(e.withdrawnOn && e.withdrawnOn <= day)).map(e => e.studentId));
  const linked = occurrences.filter(o => occurrenceLinks.get(o.occurrenceUuid)?.classSessionId === session.id);
  const start = Date.parse(session.startsAt), end = Date.parse(session.endsAt);
  const seen = new Map();
  let unmatchedPeople = 0;
  for (const o of linked) for (const person of zoomPeople.get(o.occurrenceUuid) ?? []) {
    const { match, student } = resolvePerson(person);
    if (match === "UNMATCHED") unmatchedPeople++;
    if (!student) continue;
    const from = Math.max(start, Date.parse(person.firstSeenAt)), to = Math.min(end, Date.parse(person.lastSeenAt));
    seen.set(student.id, { visits: person.visits, openVisits: person.openVisits, connectedSeconds: Math.max(0, (to - from) / 1000),
      firstJoinAt: person.firstSeenAt, secondsAfterStart: Math.max(0, (Date.parse(person.firstSeenAt) - start) / 1000) });
  }
  const final = finalisedClasses.get(session.id);
  const row = (student, expected) => ({ studentId: student.id, studentNumber: student.studentNumber, fullName: student.fullName, expected,
    seen: seen.has(student.id), ...(seen.get(student.id) ?? { visits: 0, openVisits: 0, connectedSeconds: 0, firstJoinAt: null, secondsAfterStart: null }),
    recordId: final?.outcomes.get(student.id)?.id ?? null, outcome: final?.outcomes.get(student.id)?.outcome ?? null,
    recordVersion: final?.outcomes.get(student.id)?.version ?? null });
  const rows = [...students.filter(s => expectedIds.has(s.id)).map(s => row(s, true)),
    ...students.filter(s => seen.has(s.id) && !expectedIds.has(s.id)).map(s => row(s, false))]
    .sort((a, b) => Number(b.expected) - Number(a.expected) || a.studentNumber.localeCompare(b.studentNumber));
  const reported = linked.filter(o => zoomReports.has(o.occurrenceUuid)).length;
  return { sessionId: session.id, status: linked.length > 0 && reported === linked.length ? "RECONCILED" : "PROVISIONAL",
    classStartsAt: session.startsAt, classEndsAt: session.endsAt, linkedOccurrences: linked.length, reportedOccurrences: reported,
    webinarEnded: linked.length > 0, unmatchedPeople, calculatedAt: new Date().toISOString(), students: rows, finalisedAt: final?.at ?? null };
}

// Monitored Zoom webinars (Zoom owns schedules). Zoom's side: a recurring webinar started two hours before its scheduled
// time (live now), and one with no fixed time. Monitoring the first creates its live run and one earlier, reported run.
const zoomWebinars = {
  "84400000001": () => ({ topic: "A/L Physics 2027", type: 9, durationMinutes: 120, timeZone: "Asia/Colombo",
    scheduledStartAt: new Date(Date.now() + 95 * 60_000).toISOString(),
    occurrences: [1, 8, 15].map((d, i) => ({ occurrenceId: `1700${i}`, startTime: new Date(Date.now() + 95 * 60_000 + (d - 1) * 7 * 86400_000).toISOString(),
      durationMinutes: 120, status: "available" })) }),
  "84400000002": () => ({ topic: "Office hours", type: 6, durationMinutes: 60, timeZone: "Asia/Colombo", scheduledStartAt: null, occurrences: [] }),
};
const monitors = [];
const runSessions = new Map(); // class id -> run
const onlineHistories = new Map(); // class id -> observed matched student counts
function monitorView(m) {
  const runs = [...runSessions.values()].filter(r => r.monitorId === m.id).sort((a, b) => b.firstSeenAt.localeCompare(a.firstSeenAt))
    .map(r => { const copy = { ...r }; delete copy.monitorId; return copy; });
  const live = runs.find(r => r.state === "LIVE") ?? null;
  return { ...m, classResolved: !!m.academyClassName, live, runs };
}
function monitorsApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  const manager = roles.includes("STAFF");
  const runPage = req.url.match(/^\/api\/v1\/class-sessions\/([^/]+)\/zoom-run$/);
  if (runPage) {
    const run = runSessions.get(runPage[1]);
    if (!run) return send(204);
    const m = monitors.find(x => x.id === run.monitorId);
    return send(200, { webinarId: m.webinarId, topic: m.topic, occurrenceUuid: run.occurrenceUuid, state: run.state, firstSeenAt: run.firstSeenAt,
      actualStartedAt: run.actualStartedAt, actualEndedAt: run.actualEndedAt, scheduledStartAt: m.scheduledStartAt, scheduledDurationMinutes: m.durationMinutes,
      timeZone: m.timeZone, reported: run.reported, connectedNow: run.connectedNow, people: run.people, academyClassName: m.academyClassName,
      classResolved: !!m.academyClassName });
  }
  if (!manager) return send(403, { detail: "Forbidden" });
  if (req.url === "/api/v1/zoom/monitors" && req.method === "GET") return send(200, monitors.map(monitorView));
  if (req.url === "/api/v1/zoom/monitors" && req.method === "POST") {
    const text = String(body.webinar ?? "").trim();
    const plain = text.replace(/[\s-]/g, "");
    const link = text.startsWith("https://") ? text.match(/\/(?:j|w|s|wc\/join|webinar)\/(\d{9,20})(?:[/?#]|$)/) : null;
    const id = /^\d{9,20}$/.test(plain) ? plain : link ? link[1] : null;
    if (!id) return send(400, { detail: "Paste the webinar's join link or its ID (9 to 20 digits)." });
    if (!zoomWebinars[id]) return send(404, { detail: "Webinar in the academy's Zoom account not found" });
    let m = monitors.find(x => x.webinarId === id);
    const zoom = zoomWebinars[id]();
    if (!m) {
      const exact = Object.keys(academySource).filter(name => name.toLowerCase() === zoom.topic.toLowerCase());
      m = { id: uuid(), webinarId: id, ...zoom, recurring: zoom.type !== 5, syncStatus: "OK", syncedAt: new Date().toISOString(), syncError: null,
        academyClassName: exact.length === 1 ? exact[0] : null, classSource: exact.length === 1 ? "EXACT_TITLE" : null,
        classBatchId: exact.length === 1 ? academyLinks.get(exact[0])?.batchId ?? null : null, active: true, addedAt: new Date().toISOString(), version: 0 };
      delete m.type;
      monitors.push(m);
      if (id === "84400000001") {
        for (const [state, minutesAgo, length] of [["ENDED", 7 * 24 * 60, 110], ["LIVE", 25, null]]) {
          const started = new Date(Date.now() - minutesAgo * 60_000).toISOString();
          const ended = length ? new Date(Date.now() - (minutesAgo - length) * 60_000).toISOString() : null;
          const session = { id: uuid(), title: m.topic, startsAt: started, endsAt: ended ?? new Date(Date.parse(started) + 12 * 3600_000).toISOString(),
            timeZone: "Asia/Colombo", status: "SCHEDULED", zoomWebinarId: id, seriesId: null, batchIds: [], origin: "ZOOM_RUN", version: 0 };
          classSessions.push(session);
          runSessions.set(session.id, { monitorId: m.id, occurrenceUuid: `run-${state}-${session.id.slice(-4)}==`, classSessionId: session.id,
            state, firstSeenAt: started, actualStartedAt: started, actualEndedAt: ended, reported: state === "ENDED",
            connectedNow: state === "LIVE" ? 12 : 0, people: state === "LIVE" ? 15 : 18 });
          if (state === "LIVE") {
            onlineHistories.set(session.id, [[1, 4], [6, 8], [24, 12]].map(([minutes, online]) => ({
              at: new Date(Date.parse(started) + minutes * 60_000).toISOString(), online,
            })));
          } else {
            onlineHistories.set(session.id, Array.from({ length: length * 2 + 1 }, (_, i) => ({
              at: new Date(Date.parse(started) + i * 30_000).toISOString(),
              online: i === length * 2 ? 0 : Math.min(i + 1, 18),
            })));
          }
        }
      }
    } else {
      m.active = true;
    }
    return send(200, monitorView(m));
  }
  const action = req.url.match(/^\/api\/v1\/zoom\/monitors\/([^/]+)\/(sync|stop|resume|academy-class)$/);
  const m = action && monitors.find(x => x.id === action[1]);
  if (!m) return send(404, { detail: "Monitored webinar not found" });
  if (action[2] === "sync") Object.assign(m, { syncedAt: new Date().toISOString(), syncStatus: "OK" });
  if (action[2] === "stop") m.active = false;
  if (action[2] === "resume") m.active = true;
  if (action[2] === "academy-class") {
    const name = body.academyClassName ? String(body.academyClassName).trim() : null;
    if (name && !academySource[name]) return send(404, { detail: "Academy class not found" });
    Object.assign(m, { academyClassName: name, classSource: name ? "ADMIN" : null, classBatchId: name ? academyLinks.get(name)?.batchId ?? null : null });
  }
  return send(200, monitorView(m));
}

// Attendance dashboards: two finished classes, three students. The Academy's example: Amaya joins three times in the
// first class (25 + 25 + 20 minutes = 70 of 120).
const insightDay = days => { const d = new Date(Date.now() - days * 86400_000); d.setUTCHours(3, 30, 0, 0); return d; };
const INSIGHT_CLASSES = [
  { id: "00000000-0000-4000-8000-0000000000c1", title: "Waves and oscillations", start: insightDay(8), minutes: 120, state: "REPORT" },
  { id: "00000000-0000-4000-8000-0000000000c2", title: "Electric fields", start: insightDay(3), minutes: 120, state: "FINAL" },
];
const INSIGHT_STUDENTS = [
  { id: "00000000-0000-4000-8000-0000000000b1", number: "PCA101", name: "Amaya Perera", visits: [[[0, 25], [35, 60], [70, 90]], [[0, 120]]], outcomes: [null, "PRESENT"] },
  { id: "00000000-0000-4000-8000-0000000000b2", number: "PCA102", name: "Kavin Silva", visits: [[[10, 50]], [[0, 30], [40, 60]]], outcomes: [null, "ABSENT"] },
  { id: "00000000-0000-4000-8000-0000000000b3", number: "PCA103", name: "Nila Fernando", visits: [[], []], outcomes: [null, "ABSENT"] },
];
const iso = (start, minute) => new Date(start.getTime() + minute * 60_000).toISOString();
const insightCell = (student, i) => {
  const seconds = student.visits[i].reduce((sum, [a, b]) => sum + (b - a) * 60, 0);
  return { connectedSeconds: seconds, percent: Math.round(seconds / (INSIGHT_CLASSES[i].minutes * 60) * 100), visits: student.visits[i].length,
    outcome: student.outcomes[i], connectedNow: false };
};
function insightsOverview(student) {
  const classes = INSIGHT_CLASSES.map((c, i) => {
    const cell = insightCell(student, i);
    return { id: c.id, title: c.title, startsAt: c.start.toISOString(), endsAt: iso(c.start, c.minutes), timeZone: "Asia/Colombo", batchCodes: ["MON"],
      classSeconds: c.minutes * 60, state: c.state, connectedSeconds: cell.connectedSeconds, percent: cell.percent, visits: cell.visits,
      rejoins: Math.max(0, cell.visits - 1), connectedNow: false, firstJoinAt: student.visits[i][0] ? iso(c.start, student.visits[i][0][0]) : null,
      secondsAfterStart: student.visits[i][0] ? student.visits[i][0][0] * 60 : null, outcome: cell.outcome,
      timeline: student.visits[i].map(([a, b]) => ({ joinedAt: iso(c.start, a), leftAt: iso(c.start, b), open: false, secondsInClass: (b - a) * 60 })) };
  }).reverse();
  const connected = classes.reduce((s, c) => s + c.connectedSeconds, 0);
  const total = classes.reduce((s, c) => s + c.classSeconds, 0);
  const totals = { batchId: "00000000-0000-4000-8000-0000000000d1", batchCode: "MON", courseCode: "PHY-2026", classes: classes.length,
    joined: classes.filter(c => c.visits).length, connectedSeconds: connected, classSeconds: total, percent: Math.round(connected / total * 100),
    present: classes.filter(c => c.outcome === "PRESENT").length, late: 0, absent: classes.filter(c => c.outcome === "ABSENT").length, excused: 0,
    rejoins: classes.reduce((s, c) => s + c.rejoins, 0) };
  return { studentId: student.id, studentNumber: student.number, fullName: student.name, from: "2026-01-01", to: "2026-12-31", minPercent: 75,
    overall: { ...totals, batchId: null, batchCode: null, courseCode: null }, byBatch: [totals],
    enrolments: [{ batchId: totals.batchId, batchCode: "MON", batchName: "Monday evening", courseId: "00000000-0000-4000-8000-0000000000e1",
      courseCode: "PHY-2026", courseTitle: "A/L Physics 2026", enrolledOn: "2026-01-05", withdrawnOn: null, status: "ENROLLED" }],
    classes };
}
function insightsApi(req, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  const url = new URL(req.url, "http://mock");
  if (!roles.includes("STAFF")) return send(403);
  const latestOnly = url.pathname === "/api/v1/attendance/latest-completed";
  const recentOnly = url.pathname === "/api/v1/attendance/recent-completed";
  if (url.pathname === "/api/v1/attendance/class-totals" || latestOnly || recentOnly) {
    const from = url.searchParams.get("from"), to = url.searchParams.get("to");
    const inRange = start => {
      const date = localDate(start, "Asia/Colombo");
      return latestOnly || recentOnly || (from <= date && date <= to);
    };
    const fixtures = INSIGHT_CLASSES.flatMap((c, i) => inRange(c.start) ? [{
      id: c.id, title: c.title, endsAt: iso(c.start, c.minutes), timeZone: "Asia/Colombo",
      expected: INSIGHT_STUDENTS.length,
      joined: INSIGHT_STUDENTS.filter(s => s.visits[i].length > 0).length,
    }] : []);
    const sessions = classSessions.filter(s => s.status === "SCHEDULED" && Date.parse(s.endsAt) <= Date.now() && inRange(s.startsAt));
    const totals = [...fixtures, ...sessions.map(s => {
      const expected = mockAttendance(s).students.filter(row => row.expected);
      return { id: s.id, title: s.title, endsAt: s.endsAt, timeZone: s.timeZone, expected: expected.length,
        joined: expected.filter(row => row.visits > 0).length };
    })];
    if (latestOnly || recentOnly) {
      totals.sort((a, b) => Date.parse(b.endsAt) - Date.parse(a.endsAt) || b.id.localeCompare(a.id));
      if (recentOnly) return send(200, totals.slice(0, 5));
      return totals.length ? send(200, totals[0]) : send(204);
    }
    return send(200, totals.map(({ id, expected, joined }) => ({ id, expected, joined })));
  }
  if (url.pathname !== "/api/v1/attendance/dashboard") {
    const student = INSIGHT_STUDENTS.find(s => url.pathname.includes(s.id));
    if (student) return send(200, insightsOverview(student));
    const id = url.pathname.split("/")[4];
    return students.some(row => row.id === id) ? send(200, { classes: [] }) : send(404);
  }
  const from = url.searchParams.get("from"), to = url.searchParams.get("to");
  if (from && to && (Date.parse(to) - Date.parse(from)) / 86400_000 > 91) return send(400, { detail: "from must not be after to, and the range is at most 92 days" });
  const empty = url.searchParams.get("batchId") === "00000000-0000-4000-8000-0000000000d2";
  const classes = empty ? [] : INSIGHT_CLASSES.filter(c => !from || c.start.toISOString().slice(0, 10) >= from).map((c, i) => {
    const cells = INSIGHT_STUDENTS.map(s => insightCell(s, i));
    return { id: c.id, title: c.title, startsAt: c.start.toISOString(), endsAt: iso(c.start, c.minutes), timeZone: "Asia/Colombo", batchCodes: ["MON"],
      classSeconds: c.minutes * 60, state: c.state, expected: 3, joined: cells.filter(x => x.visits).length,
      averagePercent: Math.round(cells.reduce((s, x) => s + x.percent, 0) / 3) };
  });
  const indexes = classes.map(c => INSIGHT_CLASSES.findIndex(x => x.id === c.id));
  const rows = classes.length === 0 ? [] : INSIGHT_STUDENTS.map(s => {
    const cells = indexes.map(i => insightCell(s, i));
    const connected = cells.reduce((sum, x) => sum + x.connectedSeconds, 0);
    const total = classes.reduce((sum, c) => sum + c.classSeconds, 0);
    return { studentId: s.id, studentNumber: s.number, fullName: s.name, batchCodes: ["MON"], classes: cells.length, joined: cells.filter(x => x.visits).length,
      connectedSeconds: connected, classSeconds: total, percent: Math.round(connected / total * 100),
      present: cells.filter(x => x.outcome === "PRESENT").length, late: 0, absent: cells.filter(x => x.outcome === "ABSENT").length, excused: 0,
      rejoins: cells.reduce((sum, x) => sum + Math.max(0, x.visits - 1), 0), cells };
  });
  const average = rows.length ? Math.round(rows.reduce((sum, x) => sum + x.percent, 0) / rows.length) : 0;
  return send(200, { from, to, courseId: url.searchParams.get("courseId"), batchId: url.searchParams.get("batchId"), truncated: false,
    totals: { classes: classes.length, students: rows.length, averagePercent: average, classSeconds: classes.reduce((sum, c) => sum + c.classSeconds, 0),
      belowThreshold: rows.filter(x => x.percent < 75).length, minPercent: 75, connectedNow: 0 }, classes, students: rows });
}

// The Academy's own records (read-only in the real API): classes from payment rows, students by Academy ID.
const academySource = {
  "A/L Physics 2027": [["PCA101", "Amaya Perera", "amaya@example.invalid"], ["PCA102", "Kavin Silva", null], ["PCA103", null, null]],
  "Admission": [["PCA104", "Nila Fernando", "nila@example.invalid"]],
};
const academyLinks = new Map();

function academyApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  if (!roles.includes("STAFF")) return send(403, { detail: "Forbidden" });
  if (req.url === "/api/v1/academy/classes" && req.method === "GET") {
    return send(200, Object.entries(academySource).sort(([a], [b]) => a.localeCompare(b)).map(([className, rows]) => {
      const link = academyLinks.get(className);
      const batch = link && batches.find(b => b.id === link.batchId);
      return { className, studentCount: rows.length, batch: batch ? { id: batch.id, courseId: batch.courseId, code: batch.code,
        name: batch.name, active: batch.active, lastSyncedAt: link.lastSyncedAt, lastSyncedStudents: link.lastSyncedStudents } : null };
    }));
  }
  if (req.url === "/api/v1/academy/classes/link" && req.method === "PUT") {
    if (!academySource[body.className]) return send(404, { detail: "Academy class not found" });
    const batch = batches.find(b => b.id === body.batchId);
    if (!batch) return send(404, { detail: "Batch not found" });
    for (const [name, link] of academyLinks) {
      if (name !== body.className && link.batchId === batch.id) return send(409, { detail: "That batch is already linked to another Academy class" });
    }
    academyLinks.set(body.className, { batchId: batch.id, lastSyncedAt: null, lastSyncedStudents: null });
    return send(200, {});
  }
  if (req.url === "/api/v1/academy/classes/sync" && req.method === "POST") {
    const link = academyLinks.get(body.className);
    if (!link) return send(404, { detail: "Link for this Academy class not found" });
    let created = 0, enrolled = 0, alreadyEnrolled = 0, withoutEmail = 0;
    for (const [number, name, email] of academySource[body.className]) {
      let student = students.find(s => s.studentNumber === number);
      if (!student) {
        const usable = email && !students.some(s => s.email === email);
        if (!usable) withoutEmail++;
        student = { id: uuid(), studentNumber: number, fullName: name ?? number, email: usable ? email : `${number.toLowerCase()}@students.academy.invalid`,
          accountId: uuid(), accountStatus: "PENDING_ACTIVATION", active: true, version: 0 };
        students.push(student);
        created++;
      }
      if (enrollments.some(e => e.batchId === link.batchId && e.studentId === student.id)) { alreadyEnrolled++; continue; }
      enrollments.push({ id: uuid(), batchId: link.batchId, studentId: student.id, enrolledOn: new Date().toISOString().slice(0, 10), withdrawnOn: null, version: 0 });
      enrolled++;
    }
    const rows = academySource[body.className];
    Object.assign(link, { lastSyncedAt: new Date().toISOString(), lastSyncedStudents: rows.length });
    return send(200, { academyStudents: rows.length, created, enrolled, alreadyEnrolled, withoutName: rows.filter(r => !r[1]).length,
      withoutEmail, withdrawnInLuma: [], inactiveInLuma: [], invalidIds: [], notInAcademyClass: [] });
  }
  return send(404);
}

function academicApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  const [path, query = ""] = req.url.split("?");
  const [, , , collection, id, sub] = path.split("/");
  if (collection === "audit-events" && req.method === "GET") {
    if (!roles.includes("STAFF")) return send(403, { detail: "Forbidden" });
    return auditApi(query, send);
  }
  const manager = roles.includes("STAFF");
  if (collection === "attendance-appeals") {
    if (!manager) return send(403, { detail: "Forbidden" });
    if (req.method === "GET") {
      const status = new URLSearchParams(query).get("status");
      return send(200, attendanceAppeals.filter(a => !status || a.status === status).map(a => ({ ...a, outcome: a.recordId === myRecord.id ? myRecord.outcome : a.outcome })).reverse());
    }
    const appeal = attendanceAppeals.find(a => a.id === id);
    if (!appeal) return send(404, { detail: "Appeal not found" });
    if (appeal.status !== "OPEN") return send(409, { detail: "This appeal has already been decided" });
    if (String(body.note ?? "").trim().length < 3) return send(400, { detail: "A note of 3 to 500 characters is required" });
    if (body.decision === "ACCEPT") {
      if (!body.outcome || body.outcome === myRecord.outcome) return send(400, { detail: "Choose a different outcome" });
      myRecord.outcome = body.outcome;
      audit("ATTENDANCE_CORRECTED", "CLASS_SESSION", myRecord.classSessionId, myRecord.classTitle, { from: appeal.outcome, to: body.outcome, reason: `Appeal accepted: ${body.note}` });
    }
    Object.assign(appeal, { status: body.decision === "ACCEPT" ? "ACCEPTED" : "REJECTED", resolutionNote: String(body.note).trim(), resolvedAt: new Date().toISOString() });
    audit(body.decision === "ACCEPT" ? "ATTENDANCE_APPEAL_ACCEPTED" : "ATTENDANCE_APPEAL_REJECTED", "ATTENDANCE_RECORD", appeal.recordId, null, { note: appeal.resolutionNote });
    return send(200, { ...appeal, outcome: myRecord.outcome });
  }
  if (collection === "attendance-records" && sub === "corrections" && req.method === "POST") {
    if (!manager) return send(403, { detail: "Forbidden" });
    const record = attendanceRecords.find(r => r.id === id);
    if (!record) return send(404, { detail: "Attendance record not found" });
    if (record.version !== body.version) return send(409, { detail: "This record was changed by someone else; reload and try again" });
    if (body.outcome === record.outcome) return send(400, { detail: `The record already says ${body.outcome}` });
    if (String(body.reason ?? "").trim().length < 3) return send(400, { detail: "A reason of 3 to 500 characters is required" });
    const session = classSessions.find(c => c.id === record.sessionId);
    audit("ATTENDANCE_CORRECTED", "CLASS_SESSION", record.sessionId, session?.title ?? null, { from: record.outcome, to: body.outcome, reason: String(body.reason).trim() });
    Object.assign(record, { outcome: body.outcome, version: record.version + 1 });
    return send(200, record);
  }
  if (collection === "engagement-settings") {
    if (!manager) return send(403, { detail: "Forbidden" });
    if (req.method === "GET") return engagementSettings ? send(200, engagementSettings) : send(204);
    if (!roles.includes("STAFF")) return send(403, { detail: "Staff role required" });
    if ((engagementSettings?.version ?? null) !== (body.version ?? null)) return send(409, { detail: "Changed by someone else" });
    const newWording = engagementSettings && engagementSettings.consentText !== body.consentText;
    engagementSettings = { enabled: body.enabled, consentText: body.consentText, consentVersion: (engagementSettings?.consentVersion ?? 1) + (newWording ? 1 : 0),
      version: engagementSettings ? engagementSettings.version + 1 : 0, updatedAt: new Date().toISOString() };
    audit("ENGAGEMENT_SETTINGS_CHANGED", "ACADEMY", null, null, { enabled: String(body.enabled), consentVersion: String(engagementSettings.consentVersion) });
    return send(200, engagementSettings);
  }
  if (collection === "attendance-policy") {
    if (req.method === "GET") return attendancePolicy ? send(200, attendancePolicy) : send(204);
    if (!roles.includes("STAFF")) return send(403, { detail: "Staff role required" });
    if ((attendancePolicy?.version ?? null) !== (body.version ?? null)) return send(409, { detail: "The attendance rule was changed by someone else" });
    attendancePolicy = { minConnectedPercent: body.minConnectedPercent, lateAfterMinutes: body.lateAfterMinutes, description: body.description,
      autoFinalise: typeof body.autoFinalise === "boolean" ? body.autoFinalise : !!attendancePolicy?.autoFinalise,
      version: attendancePolicy ? attendancePolicy.version + 1 : 0, updatedAt: new Date().toISOString() };
    audit("ATTENDANCE_POLICY_SET", "ACADEMY", null, null, { minConnectedPercent: String(body.minConnectedPercent), lateAfterMinutes: String(body.lateAfterMinutes) });
    return send(200, attendancePolicy);
  }
  // Attendance over a period (fixed figures; the real API counts final records). Academy totals: managers and analysts.
  const summaryMethod = "Final attendance only. Rate = (present + late) / (present + late + absent); excused classes are left out.";
  const counts = (present, late, absent, excused) => ({ present, late, absent, excused,
    ratePercent: present + late + absent === 0 ? null : Math.round(100 * (present + late) / (present + late + absent)) });
  // Ended classes that are not final: with a webinar ID they count as ready (the mock has no report data), without one not linked.
  if (collection === "attendance" && (id === "pending" || id === "finalisations")) {
    if (!manager) return send(403, { detail: "Forbidden" });
    const now = Date.now();
    const waiting = classSessions.filter(c => c.status === "SCHEDULED" && Date.parse(c.endsAt) <= now && Date.parse(c.endsAt) > now - 60 * 86_400_000
      && !finalisedClasses.has(c.id)).sort((a, b) => a.startsAt.localeCompare(b.startsAt))
      .map(c => ({ sessionId: c.id, title: c.title, startsAt: c.startsAt, endsAt: c.endsAt, timeZone: c.timeZone,
        linkedRuns: c.zoomWebinarId ? 1 : 0, state: c.zoomWebinarId ? "READY" : "NO_ZOOM_RUN" }));
    if (id === "pending" && req.method === "GET") {
      return send(200, { ruleRecorded: !!attendancePolicy, autoFinalise: !!attendancePolicy?.autoFinalise, classes: waiting });
    }
    if (id === "finalisations" && req.method === "POST") {
      if (!attendancePolicy) return send(409, { detail: "The academy has not recorded its attendance rule yet" });
      const ready = waiting.filter(c => c.state === "READY");
      for (const c of ready) {
        finalisedClasses.set(c.sessionId, { at: new Date().toISOString() });
        audit("ATTENDANCE_FINALISED", "CLASS_SESSION", c.sessionId, c.title, { present: "0", late: "0", absent: "0", automatic: "false" });
      }
      return send(200, { finalised: ready.length, notReady: waiting.length - ready.length, problems: [] });
    }
    return send(405);
  }
  if (collection === "attendance" && id === "summary" && req.method === "GET") {
    if (!manager) return send(403, { detail: "Staff role required" });
    const params = new URLSearchParams(query);
    if (!params.get("from") || !params.get("to") || params.get("to") < params.get("from")) return send(400, { detail: "from and to are required" });
    const withClasses = batches.filter(b => classSessions.some(c => c.batchIds.includes(b.id)));
    return send(200, { from: params.get("from"), to: params.get("to"), method: summaryMethod,
      batches: withClasses.map(b => ({ batchId: b.id, batchCode: b.code, batchName: b.name,
        courseCode: courses.find(c => c.id === b.courseId)?.code ?? "", courseTitle: courses.find(c => c.id === b.courseId)?.title ?? "",
        finalClasses: 2, classesNotFinal: 1, totals: counts(5, 1, 2, 1) })) });
  }
  if (collection === "batches" && path.endsWith("/attendance/summary") && req.method === "GET") {
    if (!manager) return send(403, { detail: "Forbidden" });
    if (!batches.some(b => b.id === id)) return notFound();
    const params = new URLSearchParams(query);
    if (!params.get("from") || !params.get("to") || params.get("to") < params.get("from")) return send(400, { detail: "from and to are required" });
    const rows = enrollments.filter(e => e.batchId === id).map((e, i) => {
      const student = students.find(st => st.id === e.studentId);
      return { studentId: e.studentId, studentNumber: student?.studentNumber ?? "", fullName: student?.fullName ?? "",
        counts: i % 2 === 0 ? counts(1, 0, 1, 0) : counts(2, 0, 0, 0) };
    }).sort((a, b) => (a.counts.ratePercent ?? 101) - (b.counts.ratePercent ?? 101) || a.studentNumber.localeCompare(b.studentNumber));
    const sum = rows.reduce((t, r) => counts(t.present + r.counts.present, t.late + r.counts.late, t.absent + r.counts.absent,
      t.excused + r.counts.excused), counts(0, 0, 0, 0));
    return send(200, { from: params.get("from"), to: params.get("to"), finalClasses: rows.length ? 2 : 0, classesNotFinal: 0,
      totals: sum, students: rows, method: summaryMethod });
  }
  if (!manager) return send(403, { detail: "Forbidden" });
  if (req.method === "GET" && path.endsWith("/attendance/export")) {
    // The real export has more columns and one row per student; the mock proves the download path.
    const header = "class_date,class_title,data_source,student_number,student_name,minutes_connected\r\n";
    if (collection === "class-sessions") {
      const session = classSessions.find(s => s.id === id);
      if (!session) return send(404, { detail: "Not found" });
      audit("ATTENDANCE_EXPORTED", "CLASS_SESSION", session.id, session.title, { scope: "class", rows: "0" });
      return send.csv(`attendance-${localDate(session.startsAt, session.timeZone)}.csv`, header);
    }
    const batch = batches.find(b => b.id === id);
    const params = new URLSearchParams(query);
    if (!batch) return send(404, { detail: "Not found" });
    if (!params.get("from") || !params.get("to") || params.get("to") < params.get("from")) {
      return send(400, { detail: "from and to are required, to not before from, at most 92 days" });
    }
    audit("ATTENDANCE_EXPORTED", "BATCH", batch.id, batch.code, { scope: "batch", from: params.get("from"), to: params.get("to"), rows: "0" });
    return send.csv(`attendance-${batch.code.toLowerCase()}-${params.get("from")}-to-${params.get("to")}.csv`, header);
  }
  function stale() { return send(409, { detail: "The record was changed by someone else; reload and retry" }); }
  function notFound() { return send(404, { detail: "Not found" }); }

  if (collection === "student-invitations") return studentInvitations(req, body, send, id);

  if (collection === "students" && sub === "erasure" && req.method === "POST") {
    if (!roles.includes("STAFF")) return send(403, { detail: "Staff role required" });
    const student = students.find(s => s.id === id);
    if (!student) return send(404, { detail: "Student not found" });
    if (String(body.confirmStudentNumber ?? "").trim().toUpperCase() !== student.studentNumber) return send(400, { detail: "Type the student's number to confirm" });
    const placeholder = `ERASED-${student.id.slice(0, 8).toUpperCase()}`;
    Object.assign(student, { studentNumber: placeholder, fullName: "Erased student", email: `erased-${student.accountId}@erased.invalid`, active: false, accountStatus: "DISABLED" });
    audit("STUDENT_ERASED", "STUDENT", student.id, null, { placeholder, reason: String(body.reason ?? "") });
    return send(200, { studentId: student.id, placeholderNumber: placeholder, zoomRowsCleared: 0 });
  }
  if (collection === "students" && sub === "attendance" && req.method === "GET") {
    return send(200, attendanceRecords.filter(r => r.studentId === id).map(r => {
      const session = classSessions.find(c => c.id === r.sessionId);
      return { id: r.id, classSessionId: r.sessionId, classTitle: session.title, classStartsAt: session.startsAt, classEndsAt: session.endsAt,
        timeZone: session.timeZone, computedOutcome: r.computedOutcome, outcome: r.outcome, connectedSeconds: r.connectedSeconds, firstJoinAt: null,
        secondsAfterStart: null, finalisedAt: r.finalisedAt, version: r.version, appealStatus: null };
    }));
  }
  if (collection === "students") {
    if (req.method === "GET" && !id) {
      const params = new URLSearchParams(query);
      const limit = Number(params.get("limit") ?? 50);
      const after = (params.get("after") ?? "").toUpperCase();
      const active = params.get("active");
      const q = (params.get("q") ?? "").trim().toLowerCase();
      const matching = students.filter(s => (active === null || String(s.active) === active) && s.studentNumber > after
        && (!q || s.studentNumber.toLowerCase().includes(q) || s.fullName.toLowerCase().includes(q) || (q.includes("@") && s.email === q)))
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

  if ((collection === "batches" || collection === "courses") && sub === "engagement-ranking" && req.method === "GET" && path.endsWith("/export")) {
    const params = new URLSearchParams(query);
    audit("ENGAGEMENT_RANKING_EXPORTED", collection === "courses" ? "COURSE" : "BATCH", id, null, { from: params.get("from"), to: params.get("to") });
    return send.csv(`engagement-ranking-${params.get("from")}-to-${params.get("to")}.csv`, "rank,student_number,student_name,classes,reactions,pulse_answers,score\r\n");
  }
  if (collection === "courses" && sub === "engagement-ranking" && req.method === "GET") {
    return send(200, { limit: 10, classes: 0, studentsCounted: 0, top: [], method: "(reactions per class, at most 10) + 3 × pulse answers, divided by classes expected" });
  }
  if (collection === "batches" && sub === "engagement-ranking" && req.method === "GET") {
    const params = new URLSearchParams(query);
    if (!["10", "100"].includes(params.get("limit") ?? "10")) return send(400, { detail: "limit must be 10 or 100" });
    return send(200, { limit: Number(params.get("limit") ?? 10), classes: classSessions.filter(c => c.batchIds.includes(id)).length,
      studentsCounted: 0, top: [], method: "(reactions per class, at most 10) + 3 × pulse answers, divided by classes expected" });
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
      reviseLinks([created.zoomWebinarId]);
      audit("CLASS_SCHEDULED", "CLASS_SESSION", created.id, created.title, { startsAt: created.startsAt, endsAt: created.endsAt,
        timeZone: created.timeZone, batches: String(created.batchIds.length), webinar: created.zoomWebinarId ? "set" : "none" });
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
    if (sub === "engagement" && req.method === "GET") return send(200, mockEngagement(session.id, false));
    if (sub === "pulses" && req.method === "POST") {
      const questionId = path.split("/")[6];
      const list = pulses.get(session.id) ?? [];
      if (questionId) {
        const q = list.find(q => q.id === questionId);
        if (!q || !q.open) return send(409, { detail: "This question is not open" });
        q.open = false;
        return send(204);
      }
      if (!engagementSettings?.enabled) return send(409, { detail: "Engagement is not switched on at this academy" });
      const question = { id: uuid(), prompt: body.prompt, options: body.options, openedAt: new Date().toISOString(),
        closesAt: new Date(Date.now() + body.minutes * 60000).toISOString(), open: true, counts: body.options.map(() => 0), myAnswer: null };
      pulses.set(session.id, [...list, question]);
      audit("PULSE_OPENED", "CLASS_SESSION", session.id, session.title, { minutes: String(body.minutes), options: String(body.options.length) });
      return send(201, question);
    }
    if (sub === "attendance" && req.method === "GET") {
      const facts = mockAttendance(session);
      const run = runSessions.get(session.id);
      if (run) facts.webinarEnded = run.state === "ENDED";
      if (new URLSearchParams(query).get("includeOnlineHistory") === "true") facts.onlineHistory = onlineHistories.get(session.id) ?? [];
      return send(200, facts);
    }
    if (sub === "attendance" && req.method === "POST" && path.endsWith("/attendance/recalculation")) {
      const final = finalisedClasses.get(session.id);
      if (!final) return send(409, { detail: "This class is not final yet; finalise it instead" });
      if (String(body.reason ?? "").trim().length < 3) return send(400, { detail: "A reason of 3 to 300 characters is required" });
      let kept = 0;
      for (const record of final.outcomes.values()) if (record.outcome !== record.computedOutcome) kept++;
      audit("ATTENDANCE_RECALCULATED", "CLASS_SESSION", session.id, session.title, { changed: "0", kept: String(kept), added: "0", reason: String(body.reason).trim() });
      return send(200, { sessionId: session.id, changed: 0, kept, added: 0 });
    }
    if (sub === "attendance" && req.method === "POST" && path.endsWith("/attendance/finalisation")) {
      // As the API: the academy's rule, a finished class, Zoom's report for every linked occurrence, once per class.
      if (finalisedClasses.has(session.id)) return send(409, { detail: "This class's attendance is already final" });
      if (!attendancePolicy) return send(409, { detail: "The academy has not recorded its attendance rule yet" });
      if (Date.parse(session.endsAt) > Date.now()) return send(409, { detail: "The class has not finished yet" });
      const facts = mockAttendance(session);
      if (facts.status !== "RECONCILED") return send(409, { detail: "Zoom's participant report has not been fetched; live events alone are never final" });
      const length = (Date.parse(session.endsAt) - Date.parse(session.startsAt)) / 1000;
      const outcomes = new Map();
      for (const s of facts.students.filter(s => s.expected)) {
        const enough = s.connectedSeconds * 100 >= attendancePolicy.minConnectedPercent * length;
        const outcome = !enough || !s.seen ? "ABSENT" : s.secondsAfterStart > attendancePolicy.lateAfterMinutes * 60 ? "LATE" : "PRESENT";
        const record = { id: uuid(), sessionId: session.id, studentId: s.studentId, computedOutcome: outcome, outcome, version: 0,
          connectedSeconds: s.connectedSeconds, finalisedAt: new Date().toISOString() };
        attendanceRecords.push(record);
        outcomes.set(s.studentId, record);
      }
      finalisedClasses.set(session.id, { at: new Date().toISOString(), outcomes });
      const count = o => [...outcomes.values()].filter(v => v.computedOutcome === o).length;
      audit("ATTENDANCE_FINALISED", "CLASS_SESSION", session.id, session.title, { present: String(count("PRESENT")), late: String(count("LATE")), absent: String(count("ABSENT")) });
      return send(200, { sessionId: session.id, finalisedAt: new Date().toISOString(), present: count("PRESENT"), late: count("LATE"), absent: count("ABSENT") });
    }
    if (req.method === "GET") return send(200, sessionView(session));
    if (req.method === "PUT") {
      if (body.status !== "SCHEDULED" && body.status !== "CANCELLED") return send(400, { detail: "status must be SCHEDULED or CANCELLED" });
      if (session.version !== body.version) return stale();
      if (classSessionProblem(body, session.batchIds, send)) return;
      const webinarBefore = session.zoomWebinarId;
      Object.assign(session, { title: String(body.title).trim(), startsAt: body.startsAt, endsAt: body.endsAt, timeZone: body.timeZone,
        status: body.status, zoomWebinarId: body.zoomWebinarId?.replace(/[\s-]/g, "") || null, batchIds: [...body.batchIds], version: session.version + 1 });
      reviseLinks([webinarBefore, session.zoomWebinarId]);
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

// A fixed port (web port + 1000) so end-to-end tests can create history directly (tests/e2e/support/classes.ts).
const mockApiPort = Number(process.env.LUMA_PLAYWRIGHT_PORT ?? "3090") + 1000;
await new Promise((resolve, reject) => backend.listen(mockApiPort, "127.0.0.1", resolve).once("error", reject));
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
      LUMA_STUDENT_ID_LOGIN_ENABLED: "true",
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
