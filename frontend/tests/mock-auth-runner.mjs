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

  if (req.url.startsWith("/api/v1/courses") || req.url.startsWith("/api/v1/batches")) {
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
function academicApi(req, body, send) {
  if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
  if (!roles.some(role => role === "ACADEMY_OWNER" || role === "ACADEMY_ADMIN")) return send(403, { detail: "Forbidden" });
  const [, , , collection, id] = req.url.split("?")[0].split("/");
  const list = collection === "courses" ? courses : batches;
  const stale = () => send(409, { detail: "The record was changed by someone else; reload and retry" });
  if (req.method === "GET" && !id) return send(200, list);
  const found = id && list.find(item => item.id === id);
  if (req.method === "GET") return found ? send(200, found) : send(404, { detail: "Not found" });
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
    if (!found) return send(404, { detail: "Not found" });
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
