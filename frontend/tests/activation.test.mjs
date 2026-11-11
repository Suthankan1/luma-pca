import assert from "node:assert/strict";
import test from "node:test";
import { activationPath, activationProblem, extractActivationToken } from "../src/lib/session-policy.ts";

const code = "Ab3_-".repeat(8) + "xyz";

test("the activation code is read from a bare code or a whole invitation link", () => {
  assert.equal(extractActivationToken(code), code);
  assert.equal(extractActivationToken(`  ${code}\n`), code);
  assert.equal(extractActivationToken(`https://luma.example/activate#token=${code}`), code);
  assert.equal(extractActivationToken(`/activate#token=${code}&other=1`), code);
  assert.equal(extractActivationToken(`https://luma.example/activate#token=${code} `), code);
});

test("anything that is not a plausible code is rejected", () => {
  for (const bad of [null, undefined, "", "   ", "short", "token=", "has space in the middle of it ok",
    "x".repeat(129), `${code}<script>`, "https://luma.example/activate", "token=ab$cdefghijklmnopqrstuvwxyz"]) {
    assert.equal(extractActivationToken(bad), null, String(bad));
  }
});

test("form problems are found before calling the server, in a helpful order", () => {
  const strong = "quiet river under bridge";
  assert.equal(activationProblem(null, strong, strong), "token");
  assert.equal(activationProblem(code, strong, `${strong}!`), "mismatch");
  assert.equal(activationProblem(code, "short", "short"), "weak");
  assert.equal(activationProblem(code, "x".repeat(129), "x".repeat(129)), "weak");
  assert.equal(activationProblem(code, "😀".repeat(12), "😀".repeat(12)), null, "length counts characters, not code units");
  assert.equal(activationProblem(code, strong, strong), null);
});

test("redirects keep the code only in the fragment", () => {
  assert.equal(activationPath(), "/activate");
  assert.equal(activationPath("invalid"), "/activate?error=invalid");
  assert.equal(activationPath("weak", code), `/activate?error=weak#token=${code}`);
  assert.equal(activationPath("weak", code).split("#")[0].includes(code), false);
});
