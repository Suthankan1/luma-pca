// L1 / L5: students sign in through the Next.js BFF at class start, then use the portal.
// Each iteration is one student: form post to /api/session/login (cookies, 303 redirect),
// then the student portal page a few times. Run via scripts/load-test-local.sh or k6.
//
// Env: BASE_URL, WEB_ORIGIN (if different), STUDENTS (600), WINDOW_S (600: arrivals spread over it, half in the
// first 30%), PORTAL_VIEWS (3), THINK_S (20), PASSWORD, PER_ADDRESS (1: one student per
// client address; 50 models carrier NAT).
import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";

const BASE = __ENV.BASE_URL;
// The web app's configured origin; differs from BASE_URL when k6 runs in Docker on macOS.
const ORIGIN = __ENV.WEB_ORIGIN || BASE;
const STUDENTS = Number(__ENV.STUDENTS || 600);
const WINDOW_S = Number(__ENV.WINDOW_S || 600);
const VIEWS = Number(__ENV.PORTAL_VIEWS || 3);
const THINK_S = Number(__ENV.THINK_S || 20);
const PER_ADDRESS = Number(__ENV.PER_ADDRESS || 1);
const PASSWORD = __ENV.PASSWORD;

// Half the class arrives in the first 30% of the window, the rest spread over the remainder.
const early = Math.ceil(STUDENTS / 2);
const firstStage = Math.max(1, Math.round(WINDOW_S * 0.3));
const secondStage = Math.max(1, WINDOW_S - firstStage);
export const options = {
  scenarios: {
    classStart: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: Math.min(STUDENTS, 400),
      maxVUs: STUDENTS,
      stages: [
        { target: Math.ceil((2 * early) / firstStage), duration: `${firstStage}s` },
        { target: Math.ceil((STUDENTS - early) / secondStage), duration: `${secondStage}s` },
      ],
      gracefulStop: `${VIEWS * THINK_S + 60}s`,
    },
  },
  thresholds: {
    "http_req_duration{name:sign-in}": ["p(95)<2000", "p(99)<4000"],
    "http_req_duration{name:portal}": ["p(95)<500", "p(99)<1000"],
    checks: ["rate>0.999"],
  },
  summaryTrendStats: ["count", "avg", "p(50)", "p(95)", "p(99)", "max"],
};

/** RFC 2544 benchmarking range 198.18.0.0/15: never a real visitor. */
function addressFor(index) {
  const group = Math.floor(index / PER_ADDRESS);
  return `198.${18 + Math.floor(group / 65536)}.${Math.floor(group / 256) % 256}.${group % 256}`;
}

export default function () {
  const index = exec.scenario.iterationInTest;
  if (index >= STUDENTS) return;
  const headers = { "X-Forwarded-For": addressFor(index) };
  http.cookieJar().clear(BASE);

  const signIn = http.post(`${BASE}/api/session/login`,
    { email: `load.student${index + 1}@example.invalid`, password: PASSWORD, portal: "student" },
    { headers: { ...headers, Origin: ORIGIN }, redirects: 0, tags: { name: "sign-in" } });
  const signedIn = check(signIn, {
    "sign-in redirects to the portal": r => r.status === 303 && /\/student$/.test(r.headers.Location || ""),
  });
  if (!signedIn) {
    console.warn(`sign-in: status ${signIn.status} location ${signIn.headers.Location || "-"} ${signIn.error || ""}`.trim());
    return;
  }
  for (let view = 0; view < VIEWS; view++) {
    const portal = http.get(`${BASE}/student`, { headers, redirects: 0, tags: { name: "portal" } });
    if (!check(portal, { "portal loads": r => r.status === 200 && r.body.includes("Student portal") })) {
      console.warn(`portal: status ${portal.status} location ${portal.headers.Location || "-"} ${portal.error || ""}`.trim());
    }
    sleep(THINK_S * (0.5 + Math.random()));
  }
}
