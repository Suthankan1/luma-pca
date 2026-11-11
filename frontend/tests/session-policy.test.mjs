import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import {
  cookieNames, hasPortal, isSameOrigin, openAccess, portalPath, sealAccess, sessionCookie, sessionKey,
} from "../src/lib/session-policy.ts";

test("session mutations require the configured origin and reject same-site siblings", () => {
  const origin = "https://luma.example";
  assert.equal(isSameOrigin(origin, origin, "same-origin"), true);
  assert.equal(isSameOrigin(origin, origin, null), true);
  assert.equal(isSameOrigin(null, origin, "same-origin"), false);
  assert.equal(isSameOrigin("https://other.luma.example", origin, "same-site"), false);
  assert.equal(isSameOrigin(origin, origin, "cross-site"), false);
});

test("portal preferences cannot grant a new role or redirect outside the app", () => {
  const identity = roles => ({ accountId: "account", academyId: "academy", roles });
  assert.equal(hasPortal(identity(["STUDENT"]), "staff"), false);
  assert.equal(portalPath(identity(["STUDENT"]), "staff"), "/student");
  assert.equal(portalPath(identity(["INSTRUCTOR"]), "student"), "/staff");
  assert.equal(portalPath(identity(["INSTRUCTOR", "STUDENT"]), "student"), "/student");
  assert.equal(portalPath(identity(["STUDENT"]), "//example.test"), "/student");
  assert.equal(portalPath(identity(["UNRECOGNIZED"])), "/sign-in?error=access");
});

test("access cookie encryption rejects tampering and cross-environment keys", () => {
  const key = sessionKey(randomBytes(32).toString("base64url"));
  const jwt = "header.payload.signature";
  const cookie = sealAccess(jwt, key);
  assert.notEqual(cookie, sealAccess(jwt, key));
  assert.equal(cookie.includes(jwt), false);
  assert.equal(openAccess(cookie, key), jwt);
  assert.equal(openAccess(cookie, randomBytes(32)), null);
  const bytes = Buffer.from(cookie, "base64url");
  bytes[30] ^= 1;
  assert.equal(openAccess(bytes.toString("base64url"), key), null);
  assert.throws(() => sessionKey("invalid"));
});

test("HTTPS session cookies are host-only and unavailable to browser scripts", () => {
  assert.deepEqual(cookieNames(true), { access: "__Host-luma-access", refresh: "__Host-luma-refresh" });
  assert.deepEqual(sessionCookie(true, 300), {
    path: "/", httpOnly: true, secure: true, sameSite: "strict", maxAge: 300,
  });
});
