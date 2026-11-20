import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import {
  cookieNames,
  hasPortal,
  isSameOrigin,
  openAccess,
  openAccessSession,
  portalPath,
  sealAccess,
  sessionCookie,
  sessionKey,
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
  assert.equal(portalPath(identity(["STAFF"]), "student"), "/staff");
  assert.equal(portalPath(identity(["STAFF", "STUDENT"]), "student"), "/student");
  assert.equal(portalPath(identity(["STAFF", "STUDENT"])), "/staff");
  assert.equal(hasPortal(identity(["ACADEMY_OWNER"]), "staff"), false, "a former role name opens nothing");
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
  assert.deepEqual(openAccessSession(cookie, key), { token: jwt, identity: null });

  const identity = { accountId: "acc-1", academyId: "acad-1", roles: ["STAFF"] };
  const sessionCookieVal = sealAccess({ token: jwt, identity }, key);
  assert.equal(openAccess(sessionCookieVal, key), jwt);
  assert.deepEqual(openAccessSession(sessionCookieVal, key), { token: jwt, identity });

  assert.equal(openAccess(cookie, randomBytes(32)), null);
  const bytes = Buffer.from(cookie, "base64url");
  bytes[30] ^= 1;
  assert.equal(openAccess(bytes.toString("base64url"), key), null);
  assert.throws(() => sessionKey("invalid"));
});

test("HTTPS session cookies are host-only and unavailable to browser scripts", () => {
  assert.deepEqual(cookieNames(true), {
    access: "__Host-luma-access",
    refresh: "__Host-luma-refresh",
  });
  assert.deepEqual(sessionCookie(true, 300), {
    path: "/", httpOnly: true, secure: true, sameSite: "strict", maxAge: 300,
  });
});

test("a password change needs the current password and a confirmed, different new one of 12 to 128 characters", async () => {
  const { passwordChangeProblem } = await import("../src/lib/session-policy.ts");
  assert.equal(passwordChangeProblem("", "a long new passphrase", "a long new passphrase"), "current");
  assert.equal(passwordChangeProblem("old", "a long new passphrase", "a long new passphrasf"), "mismatch");
  assert.equal(passwordChangeProblem("old", "short", "short"), "weak");
  assert.equal(passwordChangeProblem("a long new passphrase", "a long new passphrase", "a long new passphrase"), "same");
  assert.equal(passwordChangeProblem("old", "a long new passphrase", "a long new passphrase"), null);
});

test("isGuestPath identifies unauthenticated pages and ignores query fragments and protected routes", async () => {
  const { isGuestPath } = await import("../src/lib/session-policy.ts");
  assert.equal(isGuestPath("/"), true);
  assert.equal(isGuestPath("/sign-in"), true);
  assert.equal(isGuestPath("/sign-in?error=invalid"), true);
  assert.equal(isGuestPath("/activate"), true);
  assert.equal(isGuestPath("/activate#token=abc"), true);
  assert.equal(isGuestPath("/forgot-password"), true);
  assert.equal(isGuestPath("/reset-password"), true);
  assert.equal(isGuestPath("/reset-password?error=token#token=abc"), true);

  // Protected or special pages must NOT be considered guest paths
  assert.equal(isGuestPath("/staff"), false);
  assert.equal(isGuestPath("/staff/courses"), false);
  assert.equal(isGuestPath("/student"), false);
  assert.equal(isGuestPath("/sign-in/mfa"), false);
  assert.equal(isGuestPath("/account/password"), false);
});

