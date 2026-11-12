// L2: signed Zoom webhook bursts at the API, as Zoom would send them for one large class:
// N joins over JOIN_S, reconnects (20%: left then joined) during the class, then N leaves
// over LEAVE_S. Fixture payloads only (fictional participants at example.invalid).
// The worker's drain time is measured by scripts/load-test-local.sh from the database.
//
// Env: API_URL, WEBHOOK_SECRET, STUDENTS (600), JOIN_S (180), CLASS_S (120), LEAVE_S (120),
// WEBINAR_ID, OCCURRENCE (unique per run).
import http from "k6/http";
import { check } from "k6";
import crypto from "k6/crypto";
import exec from "k6/execution";

const API = __ENV.API_URL;
const SECRET = __ENV.WEBHOOK_SECRET;
const N = Number(__ENV.STUDENTS || 600);
const JOIN_S = Number(__ENV.JOIN_S || 180);
const CLASS_S = Number(__ENV.CLASS_S || 120);
const LEAVE_S = Number(__ENV.LEAVE_S || 120);
const WEBINAR = __ENV.WEBINAR_ID || "81234567890";
const OCCURRENCE = __ENV.OCCURRENCE || "loadTestOccurrence==";
const RECONNECTS = Math.round(N * 0.2);

const rate = (count, seconds) => Math.max(1, Math.ceil(count / seconds));
export const options = {
  scenarios: {
    joins: { executor: "constant-arrival-rate", exec: "join", rate: rate(N, JOIN_S), timeUnit: "1s",
      duration: `${JOIN_S}s`, preAllocatedVUs: 50, maxVUs: 300 },
    reconnects: { executor: "constant-arrival-rate", exec: "reconnect", rate: rate(RECONNECTS, CLASS_S), timeUnit: "1s",
      duration: `${CLASS_S}s`, startTime: `${JOIN_S}s`, preAllocatedVUs: 20, maxVUs: 200 },
    leaves: { executor: "constant-arrival-rate", exec: "leave", rate: rate(N, LEAVE_S), timeUnit: "1s",
      duration: `${LEAVE_S}s`, startTime: `${JOIN_S + CLASS_S}s`, preAllocatedVUs: 50, maxVUs: 300 },
  },
  thresholds: {
    "http_req_duration{name:webhook}": ["p(99)<1000"],
    "http_req_failed{name:webhook}": ["rate==0"],
  },
  summaryTrendStats: ["count", "avg", "p(50)", "p(95)", "p(99)", "max"],
};

function send(event, participant) {
  const now = Date.now();
  const body = JSON.stringify({
    event,
    event_ts: now,
    payload: {
      account_id: "LOADTEST",
      object: {
        id: WEBINAR,
        uuid: OCCURRENCE,
        participant: {
          user_id: String(16778240 + participant),
          participant_uuid: `${participant}-${now}-${Math.random().toString(36).slice(2)}`,
          user_name: `Load Student ${participant}`,
          email: `load.student${participant}@example.invalid`,
          [event.endsWith("joined") ? "join_time" : "leave_time"]: new Date(now).toISOString(),
        },
      },
    },
  });
  const timestamp = String(Math.floor(now / 1000));
  const signature = `v0=${crypto.hmac("sha256", SECRET, `v0:${timestamp}:${body}`, "hex")}`;
  const response = http.post(`${API}/api/v1/zoom/webhooks`, body, {
    headers: { "Content-Type": "application/json", "x-zm-request-timestamp": timestamp, "x-zm-signature": signature },
    tags: { name: "webhook" },
  });
  check(response, { "webhook acknowledged": r => r.status === 200 || r.status === 204 });
}

const participantIn = (count) => (exec.scenario.iterationInTest % count) + 1;

export function join() {
  if (exec.scenario.iterationInTest < N) send("webinar.participant_joined", participantIn(N));
}

export function reconnect() {
  if (exec.scenario.iterationInTest >= RECONNECTS) return;
  const participant = (exec.scenario.iterationInTest * 5) % N + 1;
  send("webinar.participant_left", participant);
  send("webinar.participant_joined", participant);
}

export function leave() {
  if (exec.scenario.iterationInTest < N) send("webinar.participant_left", participantIn(N));
}
