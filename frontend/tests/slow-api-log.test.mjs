import assert from "node:assert/strict";
import test from "node:test";
import { slowCallLine } from "../src/lib/slow-log.ts";

test("fast calls are not logged", () => {
  assert.equal(slowCallLine("GET", "/api/v1/courses", 200, 120, 800), null);
});

test("slow calls are logged with IDs masked and no query string", () => {
  const line = slowCallLine("GET",
    "/api/v1/class-sessions/3f2b8c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f/attendance?from=2026-01-01&email=someone@example.test", 200, 1234.6, 800);
  assert.equal(line, "[luma] slow api GET /api/v1/class-sessions/:id/attendance 200 1235 ms");
  assert.doesNotMatch(line, /someone|2026-01-01|3f2b8c1e/);
});
