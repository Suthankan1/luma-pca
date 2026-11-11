import assert from "node:assert/strict";
import test from "node:test";
import {
  readMfaChallenge,
  readMfaEnrollment,
  readMfaVerification,
  readTokens,
} from "../src/lib/auth-parsers.ts";

test("readTokens accepts valid token response and rejects malformed shapes", async () => {
  const valid = {
    accessToken: "valid-access-token",
    tokenType: "Bearer",
    expiresIn: 600,
    refreshToken: "valid-refresh-token",
    refreshExpiresIn: 604800,
  };
  const res = new Response(JSON.stringify(valid), { status: 200 });
  const parsed = await readTokens(res);
  assert.equal(parsed.accessToken, "valid-access-token");
  assert.equal(parsed.expiresIn, 600);
  assert.equal(parsed.refreshToken, "valid-refresh-token");
  assert.equal(parsed.refreshExpiresIn, 604800);

  // Missing fields or invalid numbers:
  await assert.rejects(
    () => readTokens(new Response(JSON.stringify({ accessToken: "abc" }), { status: 200 })),
    /Invalid session response/
  );
  await assert.rejects(
    () => readTokens(new Response(JSON.stringify({ ...valid, expiresIn: -5 }), { status: 200 })),
    /Invalid session response/
  );
});

test("readMfaChallenge validates valid MFA challenges and distinguishes from token responses", () => {
  const challenge = {
    mfaRequired: true,
    mfaToken: "opaque-challenge-xyz",
    mfaExpiresIn: 300,
    mfaEnrolled: false,
  };
  const parsed = readMfaChallenge(challenge);
  assert.equal(parsed.mfaRequired, true);
  assert.equal(parsed.mfaToken, "opaque-challenge-xyz");
  assert.equal(parsed.mfaExpiresIn, 300);
  assert.equal(parsed.mfaEnrolled, false);

  // Already enrolled challenge:
  const enrolledChallenge = { ...challenge, mfaEnrolled: true };
  assert.equal(readMfaChallenge(enrolledChallenge).mfaEnrolled, true);

  // Missing mfaRequired or false:
  assert.throws(() => readMfaChallenge({ mfaRequired: false }), /Invalid MFA challenge response/);
  // Malformed mfaToken:
  assert.throws(() => readMfaChallenge({ ...challenge, mfaToken: "" }), /Invalid MFA challenge response/);
  // Negative expiry:
  assert.throws(() => readMfaChallenge({ ...challenge, mfaExpiresIn: 0 }), /Invalid MFA challenge response/);
  // Missing enrolled status:
  assert.throws(() => readMfaChallenge({ mfaRequired: true, mfaToken: "abc", mfaExpiresIn: 300 }), /Invalid MFA challenge response/);
});

test("readMfaEnrollment parses setup secrets and otpauthUri", async () => {
  const enrollment = {
    secret: "JBSWY3DPEHPK3PXP",
    otpauthUri: "otpauth://totp/Physics%20Cube%20Academy:admin@example.invalid?secret=JBSWY3DPEHPK3PXP&issuer=Physics%20Cube%20Academy",
  };
  const res = new Response(JSON.stringify(enrollment), { status: 200 });
  const parsed = await readMfaEnrollment(res);
  assert.equal(parsed.secret, "JBSWY3DPEHPK3PXP");
  assert.match(parsed.otpauthUri, /^otpauth:\/\/totp\//);

  // Malformed enrollment:
  await assert.rejects(
    () => readMfaEnrollment(new Response(JSON.stringify({ secret: "abc" }), { status: 200 })),
    /Invalid MFA enrollment response/
  );
  await assert.rejects(
    () => readMfaEnrollment(new Response(JSON.stringify({ secret: "JBSWY3DPEHPK3PXP", otpauthUri: "https://evil.example" }), { status: 200 })),
    /Invalid MFA enrollment response/
  );
});

test("readMfaVerification parses tokens and handles optional recovery codes", async () => {
  const verifiedWithRecovery = {
    accessToken: "new-access-jwt",
    tokenType: "Bearer",
    expiresIn: 600,
    refreshToken: "new-refresh-token",
    refreshExpiresIn: 604800,
    recoveryCodes: ["code-1", "code-2", "code-3"],
  };
  const res1 = new Response(JSON.stringify(verifiedWithRecovery), { status: 200 });
  const parsed1 = await readMfaVerification(res1);
  assert.equal(parsed1.accessToken, "new-access-jwt");
  assert.deepEqual(parsed1.recoveryCodes, ["code-1", "code-2", "code-3"]);

  const verifiedWithoutRecovery = {
    accessToken: "new-access-jwt",
    tokenType: "Bearer",
    expiresIn: 600,
    refreshToken: "new-refresh-token",
    refreshExpiresIn: 604800,
    recoveryCodes: null,
  };
  const res2 = new Response(JSON.stringify(verifiedWithoutRecovery), { status: 200 });
  const parsed2 = await readMfaVerification(res2);
  assert.equal(parsed2.recoveryCodes, null);

  // Invalid recoveryCodes shape (e.g. not strings):
  const invalidRecovery = { ...verifiedWithRecovery, recoveryCodes: [123, 456] };
  await assert.rejects(
    () => readMfaVerification(new Response(JSON.stringify(invalidRecovery), { status: 200 })),
    /Invalid recovery codes in verification response/
  );
});
