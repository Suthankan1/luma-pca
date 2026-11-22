import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";

let down = false;
let identityDown = false;
let accessRejected = false;
let identityChecks = 0;
let rotation = 0;
let latestRefresh = "";
const jwt = "fake-access-token.for-bff.integration-test";
let roles = [];
const backend = createServer(async (req, res) => {
  let encoded = "";
  for await (const chunk of req) encoded += chunk;
  const body = encoded ? JSON.parse(encoded) : {};
  const send = (status, value) => {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(value ? JSON.stringify(value) : "");
  };
  if (down) return send(503);
  switch (req.url) {
    case "/api/v1/auth/login":
      if (body.password !== "example-password") return send(401);
      if (body.email === "student@example.invalid") {
        roles = ["STUDENT"];
      } else if (body.email === "staff@example.invalid") {
        roles = ["STAFF"];
      } else if (body.email === "admin@example.invalid") {
        roles = ["STAFF"];
      } else {
        return send(401);
      }
      break;
    case "/api/v1/auth/refresh":
      if (body.refreshToken !== latestRefresh) return send(401);
      break;
    case "/api/v1/auth/me":
      identityChecks++;
      if (identityDown) return send(503);
      if (accessRejected || req.headers.authorization !== `Bearer ${jwt}`) return send(401);
      return send(200, { accountId: "test-account", academyId: "test-academy", roles });
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
const port = Number(process.env.LUMA_SMOKE_PORT ?? 3082);
const base = `http://127.0.0.1:${port}`;
const origin = "https://luma.example";
const app = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-H", "127.0.0.1", "-p", String(port)], {
  stdio: "inherit",
  env: {
    ...process.env,
    LUMA_WEB_ORIGIN: origin,
    LUMA_SESSION_KEY: randomBytes(32).toString("base64url"),
    API_INTERNAL_URL: `http://127.0.0.1:${backend.address().port}`,
  },
});

try {
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(base)).ok) break; } catch {}
    if (i === 99) throw new Error("Next server did not start");
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  const jar = new Map();
  const call = async (path, options = {}) => fetch(base + path, {
    redirect: "manual",
    ...options,
    headers: { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join("; "), ...options.headers },
  });
  const post = (action, fields, requestOrigin = origin) => call(`/api/session/${action}`, {
    method: "POST", body: new URLSearchParams(fields), headers: { origin: requestOrigin },
  });
  const recordCookies = (response) => {
    const values = response.headers.getSetCookie();
    for (const value of values) {
      assert.match(value, /^__Host-luma-(access|refresh)=/);
      assert.match(value, /; Path=\//);
      assert.match(value, /; HttpOnly/);
      assert.match(value, /; Secure/);
      assert.match(value, /; SameSite=strict/i);
      assert.equal(value.includes(jwt), false);
      const cookie = value.split(";")[0];
      const name = cookie.slice(0, cookie.indexOf("="));
      if (value.includes("Max-Age=0")) {
        jar.delete(name);
      } else {
        jar.set(name, cookie.slice(cookie.indexOf("=") + 1));
      }
    }
  };
  const location = (response, path) => assert.equal(new URL(response.headers.get("location"), base).pathname, path);

  // 1. Initial student flow
  location(await call("/student"), "/sign-in");
  const login = { email: "student@example.invalid", password: "example-password", portal: "staff" };
  assert.equal((await post("login", login, "https://other.luma.example")).status, 403);
  assert.equal(rotation, 0);
  const rejected = await post("login", { ...login, password: "wrong" });
  assert.equal(rejected.headers.get("location"), "/sign-in?error=invalid&portal=staff");
  assert.equal(rejected.headers.getSetCookie().length, 0);
  const wrongPortal = await post("login", login);
  assert.equal(wrongPortal.headers.get("location"), "/sign-in?error=portal&portal=staff");
  assert.equal(wrongPortal.headers.getSetCookie().length, 0);
  assert.equal(latestRefresh, "", "revoke the session issued for the wrong portal");
  const success = await post("login", { ...login, portal: "student" });
  location(success, "/student");
  recordCookies(success);
  location(await call("/staff"), "/student");
  const staffSignIn = await call("/sign-in?portal=staff");
  assert.equal(staffSignIn.status, 200);
  assert.match(await staffSignIn.text(), /Sign out to switch accounts/);
  const student = await call("/student");
  assert.equal(student.status, 200);
  assert.match(student.headers.get("cache-control"), /no-store/);
  const html = await student.text();
  assert.match(html, /Student portal/);
  assert.equal(html.includes(jwt), false);
  assert.equal(html.includes(latestRefresh), false);

  // A still-sealed access cookie must not redirect back to a portal after the API rejects it.
  accessRejected = true;
  const expiredSignIn = await call("/sign-in?portal=student&error=expired");
  assert.equal(expiredSignIn.status, 200);
  assert.match(await expiredSignIn.text(), /Your session ended/);
  const expiredStatus = await (await call("/api/session/status")).json();
  assert.equal(expiredStatus.authenticated, false);
  accessRejected = false;

  // 2. Refresh rotation
  // 2a. The short access cookie expired: the proxy renews the session quietly and the page opens (no sign-in page).
  jar.delete("__Host-luma-access");
  const beforeRenewal = jar.get("__Host-luma-refresh");
  down = true;
  location(await call("/student"), "/sign-in"); // the API is down: nothing to renew with, nothing changed
  assert.equal(jar.get("__Host-luma-refresh"), beforeRenewal);
  assert.match(await (await call("/sign-in")).text(), /Continue session/);
  down = false;
  const beforeIdentityCheck = identityChecks;
  const renewed = await call("/student");
  assert.equal(renewed.status, 200);
  recordCookies(renewed);
  assert.equal(jar.has("__Host-luma-access"), true);
  assert.notEqual(jar.get("__Host-luma-refresh"), beforeRenewal);
  const renewedHtml = await renewed.text();
  assert.match(renewedHtml, /Student portal/);
  assert.equal(renewedHtml.includes(jwt), false);
  assert.equal(renewedHtml.includes(latestRefresh), false);
  assert.equal(identityChecks - beforeIdentityCheck, 1, "silent renewal checks identity once in the protected render");

  // 2b. The "Continue session" action still works when renewal could not happen on its own.
  jar.delete("__Host-luma-access");
  const oldRefresh = jar.get("__Host-luma-refresh");
  down = true;
  const failedRefresh = await post("refresh", { portal: "student" });
  assert.match(failedRefresh.headers.get("location"), /error=unavailable/);
  assert.equal(failedRefresh.headers.getSetCookie().length, 0);
  down = false;
  identityDown = true;
  const refreshed = await post("refresh", { portal: "student" });
  assert.match(refreshed.headers.get("location"), /error=unavailable/);
  recordCookies(refreshed);
  assert.notEqual(jar.get("__Host-luma-refresh"), oldRefresh);
  identityDown = false;
  assert.equal((await call("/student")).status, 200);

  // 3. Logout
  down = true;
  const unavailableLogout = await post("logout", {});
  assert.equal(unavailableLogout.headers.get("location"), "/sign-in?error=sign-out");
  assert.equal(unavailableLogout.headers.getSetCookie().length, 0);
  down = false;
  const logout = await post("logout", {});
  assert.equal(logout.headers.get("location"), "/?signedOut=1");
  recordCookies(logout);
  assert.equal(jar.has("__Host-luma-access"), false);
  assert.equal(jar.has("__Host-luma-refresh"), false);

  jar.set("__Host-luma-refresh", "revoked-refresh");
  const stale = await call("/sign-in?portal=staff");
  const staleHtml = await stale.text();
  assert.match(staleHtml, /name="email"/);
  assert.doesNotMatch(staleHtml, /Continue session/);
  recordCookies(stale);
  assert.equal(jar.size, 0);

  // 4. Staff sign in with email and password alone, straight into the dashboard.
  const wrongAdmin = await post("login", { email: "admin@example.invalid", password: "wrong-password", portal: "staff" });
  assert.match(wrongAdmin.headers.get("location"), /error=invalid/);
  assert.equal(wrongAdmin.headers.getSetCookie().length, 0);

  const adminLogin = await post("login", { email: "admin@example.invalid", password: "example-password", portal: "staff" });
  location(adminLogin, "/staff");
  recordCookies(adminLogin);
  assert.equal(jar.has("__Host-luma-access"), true);
  assert.equal(jar.has("__Host-luma-refresh"), true);

  // The removed second-factor routes no longer exist.
  assert.equal((await post("mfa-verify", { code: "123456" })).status, 404);

  const adminStaff = await call("/staff");
  assert.equal(adminStaff.status, 200);
  assert.match(await adminStaff.text(), /Staff dashboard/);

  // Final logout
  const finalLogout = await post("logout", {});
  recordCookies(finalLogout);

  console.log("Session HTTP smoke test passed.");
} finally {
  app.kill();
  backend.close();
}
