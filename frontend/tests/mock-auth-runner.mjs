import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";

let rotation = 0;
let latestRefresh = "";
const jwt = "fake-access-token.for-bff.e2e-test";
let roles = [];
const activatedTokens = new Set();

const backend = createServer(async (req, res) => {
  let encoded = "";
  for await (const chunk of req) encoded += chunk;
  const body = encoded ? JSON.parse(encoded) : {};
  const send = (status, value) => {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(value ? JSON.stringify(value) : "");
  };

  if (/^\/api\/v1\/(courses|batches|students|enrollments|accounts)(\/|\?|$)/.test(req.url)) {
    return academicApi(req, body, send);
  }

  switch (req.url) {
    case "/api/v1/auth/login":
      if (body.password !== "correct-password") return send(401);
      if (body.email === "student@example.invalid") {
        roles = ["STUDENT"];
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

    case "/api/v1/auth/refresh":
      if (body.refreshToken !== latestRefresh) return send(401);
      break;

    case "/api/v1/auth/me":
      if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
      return send(200, { accountId: "test-admin", academyId: "test-academy", roles });

    case "/api/v1/auth/logout":
      if (body.refreshToken === latestRefresh) latestRefresh = "";
      return send(204);

    default:
      return send(404);
  }

  latestRefresh = `fake-opaque-refresh-${++rotation}`;
  return send(200, { accessToken: jwt, refreshToken: latestRefresh, expiresIn: 300, refreshExpiresIn: 600 });
});

// A small in-memory stand-in for the academic API, with the same status codes and
// problem details as the real backend for the cases the staff screens handle.
const courses = [];
const batches = [];
let nextId = 1;
const uuid = () => `00000000-0000-4000-8000-${String(nextId++).padStart(12, "0")}`;
const students = [];
const enrollments = [];
const invitationLetters = "PQRSTVWXYZ";
let invitations = 0;
const staffRoles = ["INSTRUCTOR", "ATTENDANCE_OFFICER"];
const publicStudent = student => {
  const copy = { ...student };
  delete copy.invitationToken;
  return copy;
};
const enrollmentView = e => {
  const batch = batches.find(b => b.id === e.batchId);
  const student = students.find(s => s.id === e.studentId);
  return { ...e, courseId: batch.courseId, batchCode: batch.code, batchName: batch.name, studentNumber: student.studentNumber,
    studentName: student.fullName, status: e.withdrawnOn ? "WITHDRAWN" : "ENROLLED" };
};

function academicApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  const [path, query = ""] = req.url.split("?");
  const [, , , collection, id, sub] = path.split("/");
  const manager = roles.some(role => role === "ACADEMY_OWNER" || role === "ACADEMY_ADMIN");
  // Assigned staff may list batches; the mock has no assignments, so they see none.
  if (!manager && collection === "batches" && !id && req.method === "GET" && roles.some(r => staffRoles.includes(r))) return send(200, []);
  if (!manager) return send(403, { detail: "Forbidden" });
  const stale = () => send(409, { detail: "The record was changed by someone else; reload and retry" });
  const notFound = () => send(404, { detail: "Not found" });

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

  if (collection === "accounts" && sub === "invitations" && req.method === "POST") {
    const student = students.find(s => s.accountId === id);
    if (!student) return notFound();
    if (student.accountStatus !== "PENDING_ACTIVATION") return send(409, { detail: "Only an account pending activation can be invited" });
    const token = invitationLetters[invitations++ % invitationLetters.length].repeat(43);
    student.invitationToken = token;
    return send(201, { accountId: id, activationToken: token, activationPath: `/activate#token=${token}`,
      expiresAt: new Date(Date.now() + 72 * 3600 * 1000).toISOString() });
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
