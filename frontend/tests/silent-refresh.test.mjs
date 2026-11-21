import assert from "node:assert/strict";
import test from "node:test";
import { cookieHeaderWith, needsRefresh, refreshesOn } from "../src/lib/silent-refresh.ts";

const names = { access: "luma-access", refresh: "luma-refresh" };

test("only a session whose access cookie expired, with the refresh cookie still there, is renewed", () => {
  assert.equal(needsRefresh(n => n === "luma-refresh", names), true);
  assert.equal(needsRefresh(n => n === "luma-access" || n === "luma-refresh", names), false, "still valid");
  assert.equal(needsRefresh(() => false, names), false, "signed out: nothing to renew");
});

test("session routes and static files are left alone", () => {
  assert.equal(refreshesOn("/staff/dashboard"), true);
  assert.equal(refreshesOn("/student"), true);
  assert.equal(refreshesOn("/sign-in"), true);
  assert.equal(refreshesOn("/"), true, "home can redirect a renewed session to its dashboard");
  assert.equal(refreshesOn("/api/session/logout"), false, "signing out must not renew first");
  assert.equal(refreshesOn("/api/session/refresh"), false);
  assert.equal(refreshesOn("/_next/data/x.json"), false);
  assert.equal(refreshesOn("/physics-cube-academy-logo.jpg"), false);
});

test("this request sees the renewed cookies; other cookies are kept", () => {
  assert.equal(cookieHeaderWith("a=1; luma-refresh=old; b=2", { "luma-access": "new-a", "luma-refresh": "new-r" }),
    "a=1; b=2; luma-access=new-a; luma-refresh=new-r");
  assert.equal(cookieHeaderWith(null, { x: "1" }), "x=1");
});
