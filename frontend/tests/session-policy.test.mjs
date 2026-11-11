import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import {
  cookieNames,
  hasPortal,
  isSameOrigin,
  openAccess,
  openMfa,
  openRecovery,
  portalPath,
  sealAccess,
  sealMfa,
  sealRecovery,
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
  assert.deepEqual(cookieNames(true), {
    access: "__Host-luma-access",
    refresh: "__Host-luma-refresh",
    mfa: "__Host-luma-mfa",
    recovery: "__Host-luma-recovery",
  });
  assert.deepEqual(sessionCookie(true, 300), {
    path: "/", httpOnly: true, secure: true, sameSite: "strict", maxAge: 300,
  });
});

test("MFA challenge cookie seals and opens state, rejecting tampering and cross-purpose cookies", () => {
  const key = sessionKey(randomBytes(32).toString("base64url"));
  const state = {
    mfaToken: "challenge-token-123",
    enrolled: false,
    portal: "staff",
    secret: "JBSWY3DPEHPK3PXP",
    otpauthUri: "otpauth://totp/Physics%20Cube%20Academy:admin@example.invalid?secret=JBSWY3DPEHPK3PXP",
    expiresAt: Date.now() + 300_000,
  };
  const sealed = sealMfa(state, key);
  assert.notEqual(sealed, sealMfa(state, key));
  assert.equal(sealed.includes("challenge-token-123"), false);
  assert.equal(sealed.includes("JBSWY3DPEHPK3PXP"), false);

  const opened = openMfa(sealed, key);
  assert.deepEqual(opened, state);

  // Cross-purpose: openAccess must reject an MFA cookie
  assert.equal(openAccess(sealed, key), null);
  // Cross-purpose: openRecovery must reject an MFA cookie
  assert.equal(openRecovery(sealed, key), null);

  // Cross-key:
  assert.equal(openMfa(sealed, randomBytes(32)), null);

  // Tampered ciphertext:
  const bytes = Buffer.from(sealed, "base64url");
  bytes[35] ^= 1;
  assert.equal(openMfa(bytes.toString("base64url"), key), null);
});

test("MFA challenge cookie rejects expired challenges and malformed state", () => {
  const key = sessionKey(randomBytes(32).toString("base64url"));
  const expiredState = {
    mfaToken: "challenge-token-expired",
    enrolled: true,
    portal: null,
    expiresAt: Date.now() - 1000,
  };
  const sealedExpired = sealMfa(expiredState, key);
  assert.equal(openMfa(sealedExpired, key), null);

  // Missing or invalid mfaToken
  const invalidState = {
    mfaToken: "",
    enrolled: true,
    expiresAt: Date.now() + 60_000,
  };
  const sealedInvalid = sealMfa(invalidState, key);
  assert.equal(openMfa(sealedInvalid, key), null);
});

test("recovery codes cookie seals and opens state, rejecting tampering and expired codes", () => {
  const key = sessionKey(randomBytes(32).toString("base64url"));
  const state = {
    codes: ["1234-5678", "8765-4321", "1111-2222"],
    portal: "staff",
    expiresAt: Date.now() + 300_000,
  };
  const sealed = sealRecovery(state, key);
  assert.notEqual(sealed, sealRecovery(state, key));
  assert.equal(sealed.includes("1234-5678"), false);

  const opened = openRecovery(sealed, key);
  assert.deepEqual(opened, state);

  // Cross-purpose checks:
  assert.equal(openAccess(sealed, key), null);
  assert.equal(openMfa(sealed, key), null);

  // Expired recovery codes:
  const expired = { ...state, expiresAt: Date.now() - 1000 };
  const sealedExpired = sealRecovery(expired, key);
  assert.equal(openRecovery(sealedExpired, key), null);

  // Empty codes array:
  const emptyCodes = { ...state, codes: [] };
  const sealedEmpty = sealRecovery(emptyCodes, key);
  assert.equal(openRecovery(sealedEmpty, key), null);
});
