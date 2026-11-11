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
