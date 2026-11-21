import assert from "node:assert/strict";
import test from "node:test";
import {
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
