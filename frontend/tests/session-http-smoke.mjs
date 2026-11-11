import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";

let down = false;
let identityDown = false;
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
      if (body.email === "student@example.invalid") roles = ["STUDENT"];
      else if (body.email === "staff@example.invalid") roles = ["INSTRUCTOR"];
      else return send(401);
      break;
    case "/api/v1/auth/refresh":
      if (body.refreshToken !== latestRefresh) return send(401);
      break;
    case "/api/v1/auth/me":
      if (identityDown) return send(503);
      if (req.headers.authorization !== `Bearer ${jwt}`) return send(401);
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
  env: { ...process.env, LUMA_WEB_ORIGIN: origin, LUMA_SESSION_KEY: randomBytes(32).toString("base64url"), API_INTERNAL_URL: `http://127.0.0.1:${backend.address().port}` },
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
  const cookies = (response, deletion = false) => {
    const values = response.headers.getSetCookie();
    assert.equal(values.length, 2);
    for (const value of values) {
      assert.match(value, /^__Host-luma-(access|refresh)=/);
      assert.match(value, /; Path=\//);
      assert.match(value, /; HttpOnly/);
      assert.match(value, /; Secure/);
      assert.match(value, /; SameSite=strict/i);
      assert.equal(value.includes(jwt), false);
      const cookie = value.split(";")[0];
      const name = cookie.slice(0, cookie.indexOf("="));
      if (deletion) {
        assert.match(value, /; Max-Age=0/);
        jar.delete(name);
      } else {
        jar.set(name, cookie.slice(cookie.indexOf("=") + 1));
      }
    }
  };
  const location = (response, path) => assert.equal(new URL(response.headers.get("location"), base).pathname, path);

  location(await call("/student"), "/sign-in");
  const login = { email: "student@example.invalid", password: "example-password", portal: "staff" };
  assert.equal((await post("login", login, "https://other.luma.example")).status, 403);
  assert.equal(rotation, 0);
  const rejected = await post("login", { ...login, password: "wrong" });
  assert.match(rejected.headers.get("location"), /error=invalid/);
  assert.equal(rejected.headers.getSetCookie().length, 0);
  const success = await post("login", login);
  location(success, "/student");
  cookies(success);
  location(await call("/staff"), "/student");
  const student = await call("/student");
  assert.equal(student.status, 200);
  assert.match(student.headers.get("cache-control"), /no-store/);
  const html = await student.text();
  assert.match(html, /Student portal/);
  assert.equal(html.includes(jwt), false);
  assert.equal(html.includes(latestRefresh), false);

  jar.delete("__Host-luma-access");
  location(await call("/student"), "/sign-in");
  assert.match(await (await call("/sign-in")).text(), /Continue session/);
  const oldRefresh = jar.get("__Host-luma-refresh");
  down = true;
  const failedRefresh = await post("refresh", { portal: "student" });
  assert.match(failedRefresh.headers.get("location"), /error=unavailable/);
  assert.equal(failedRefresh.headers.getSetCookie().length, 0);
  down = false;
  identityDown = true;
  const refreshed = await post("refresh", { portal: "student" });
  assert.match(refreshed.headers.get("location"), /error=unavailable/);
  cookies(refreshed);
  assert.notEqual(jar.get("__Host-luma-refresh"), oldRefresh);
  identityDown = false;
  assert.equal((await call("/student")).status, 200);

  down = true;
  const failedLogout = await post("logout", {});
  assert.match(failedLogout.headers.get("location"), /error=sign-out/);
  assert.equal(failedLogout.headers.getSetCookie().length, 0);
  down = false;
  const logout = await post("logout", {});
  location(logout, "/sign-in");
  cookies(logout, true);
  assert.equal(latestRefresh, "");
  location(await call("/student"), "/sign-in");
  const staffLogin = await post("login", { ...login, email: "staff@example.invalid", portal: "student" });
  location(staffLogin, "/staff");
  cookies(staffLogin);
  location(await call("/student"), "/staff");
  const staff = await call("/staff");
  assert.equal(staff.status, 200);
  assert.match(await staff.text(), /Staff portal/);
  console.log("Session HTTP smoke test passed.");
} finally {
  app.kill();
  backend.close();
}
