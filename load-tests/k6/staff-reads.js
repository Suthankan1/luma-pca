// L3: instructors assigned to every batch keep reading the heavy staff pages during class:
// their batch list, a 500-student roster, the class page with every expected student, and
// the class list. Staff sign in with email and password.
//
// Env: BASE_URL, WEB_ORIGIN (if different), STAFF (20), DURATION_S (300), PASSWORD, BATCH_IDS (comma list), SESSION_ID, MONTH.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE_URL;
// The web app's configured origin; differs from BASE_URL when k6 runs in Docker on macOS.
const ORIGIN = __ENV.WEB_ORIGIN || BASE;
const STAFF = Number(__ENV.STAFF || 20);
const PASSWORD = __ENV.PASSWORD;
const BATCHES = (__ENV.BATCH_IDS || "").split(",").filter(Boolean);
const SESSION = __ENV.SESSION_ID;
const MONTH = __ENV.MONTH || "2027-01";

export const options = {
  // Keep each instructor's session across iterations (k6 clears cookies per iteration by default).
  noCookiesReset: true,
  scenarios: {
    staff: { executor: "constant-vus", vus: STAFF, duration: `${Number(__ENV.DURATION_S || 300)}s` },
  },
  thresholds: {
    "http_req_duration{name:roster}": ["p(95)<1000"],
    "http_req_duration{name:class}": ["p(95)<1000"],
    "http_req_duration{name:classes}": ["p(95)<1000"],
    "http_req_duration{name:class-types}": ["p(95)<1000"],
    "http_req_duration{name:students}": ["p(95)<1000"],
    "http_req_duration{name:reports}": ["p(95)<1000"],
    "http_req_duration{name:refresh}": ["p(95)<1000"],
    checks: ["rate>0.999"],
  },
  summaryTrendStats: ["count", "avg", "p(50)", "p(95)", "p(99)", "max"],
};

// Per virtual user: whether its cookie jar holds a session (cleared when a page redirects to sign-in).
let signedIn = false;
let reported = 0;

export default function () {
  const vu = __VU;
  const headers = { "X-Forwarded-For": `198.19.255.${vu % 250}` };
  if (!signedIn) {
    // A browser that still holds a session is sent to "Continue session", not signed in again:
    // start clean so a failed page earlier does not leave this user stuck.
    http.cookieJar().clear(BASE);
    const signIn = http.post(`${BASE}/api/session/login`,
      { email: `load.instructor${((vu - 1) % STAFF) + 1}@example.invalid`, password: PASSWORD, portal: "staff" },
      { headers: { ...headers, Origin: ORIGIN }, redirects: 0, tags: { name: "staff-sign-in" } });
    if (!check(signIn, { "staff sign-in": r => r.status === 303 && /\/staff$/.test(r.headers.Location || "") })) {
      console.warn(`staff-sign-in: status ${signIn.status} location ${signIn.headers.Location || "-"} ${signIn.error || ""}`.trim());
      sleep(5);
      return;
    }
    signedIn = true;
  }
  const pages = [
    ["courses", "/staff/courses", "COURSES"],
    ["roster", `/staff/batches/${BATCHES[Math.floor(Math.random() * BATCHES.length)]}`, "ROSTER"],
    ["class", `/staff/sessions/${SESSION}`, "SESSION DETAILS"],
    ["classes", `/staff/sessions?month=${MONTH}`, "ATTENDANCE BY CLASS"],
    ["class-types", `/staff/class-types?month=${MONTH}`, "Class Types"],
    ["students", `/staff/students?month=${MONTH}`, "Student directory"],
    ["reports", `/staff/reports?month=${MONTH}`, "Student report"],
  ];
  for (const [name, path, marker] of pages) {
    let page = http.get(`${BASE}${path}`, { headers, redirects: 0, tags: { name } });
    // An expired access token sends the browser to sign-in, where "Continue session" refreshes it.
    if (page.status === 307 && /^\/sign-in/.test(page.headers.Location || "")) {
      const refreshed = http.post(`${BASE}/api/session/refresh`, { portal: "staff" },
        { headers: { ...headers, Origin: ORIGIN }, redirects: 0, tags: { name: "refresh" } });
      check(refreshed, { "session refreshed": r => r.status === 303 && /\/staff$/.test(r.headers.Location || "") });
      page = http.get(`${BASE}${path}`, { headers, redirects: 0, tags: { name } });
    }
    const ok = check(page, { [`${name} loads`]: r => r.status === 200 && r.body.includes(marker) });
    if (!ok && reported++ < 3) console.warn(`${name}: status ${page.status} location ${page.headers.Location || "-"} ${page.error || ""}`.trim());
    if (page.status !== 200) signedIn = false;
    sleep(2 + Math.random() * 3);
  }
}
