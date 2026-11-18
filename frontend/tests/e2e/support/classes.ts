import { expect, test, type Page } from "@playwright/test";

/**
 * Classes are no longer scheduled in LUMA's screens (Zoom owns schedules). Tests that need a class with attendance history
 * create it in the mock API directly, as the backend's own tests do with manual scheduling switched on. The mock API
 * listens on the web port + 1000 (tests/mock-auth-runner.mjs); the signed-in staff member's token is the mock's fixed one.
 */
const TOKEN = "fake-access-token.for-bff.e2e-test";

export function mockApiBase() {
  const base = new URL(test.info().project.use.baseURL!);
  return `http://127.0.0.1:${Number(base.port) + 1000}`;
}

async function call(page: Page, method: "GET" | "POST", path: string, data?: unknown) {
  return page.request.fetch(mockApiBase() + path, {
    method, data, headers: { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json" },
  });
}

/** "2027-04-05" + "14:00" in Sri Lanka time (UTC+05:30, no daylight saving) as an ISO instant. */
function colombo(date: string, time: string) {
  return new Date(Date.parse(`${date}T${time}:00+05:30`)).toISOString();
}

/** @param batch the label staff see: "COURSE · BATCH · Batch name" */
export async function scheduleClass(page: Page, { title, date, start, end, batch, webinar }: {
  title: string; date: string; start: string; end: string; batch: string; webinar?: string;
}) {
  const [courseCode, batchCode] = batch.split(" · ");
  const courses = await (await call(page, "GET", "/api/v1/courses")).json() as { id: string; code: string }[];
  const batches = await (await call(page, "GET", "/api/v1/batches")).json() as { id: string; courseId: string; code: string }[];
  const course = courses.find(c => c.code === courseCode);
  const found = batches.find(b => b.courseId === course?.id && b.code === batchCode);
  expect(found, `batch ${batch}`).toBeTruthy();
  const response = await call(page, "POST", "/api/v1/class-sessions", {
    title, startsAt: colombo(date, start), endsAt: colombo(date, end), timeZone: "Asia/Colombo", batchIds: [found!.id],
    zoomWebinarId: webinar ? webinar.replace(/\s/g, "") : null,
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { id: string };
}
