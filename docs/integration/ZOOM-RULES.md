# Zoom integration — non-negotiable feasibility and reliability rules

**Status (17 Nov 2026):** all rules below are implemented and tested. Proven with real Zoom: signed webhook delivery
and endpoint validation, server-to-server OAuth, participant reports, past-occurrence lookup and matching a registered
student by email (ZOOM-TEST-WEBINAR.md). Not yet proven with real Zoom: a recurring webinar with several occurrences
(KNOWN-LIMITS.md).

1. Obtain academy-owned Zoom account admin consent and confirm Webinar licensing and report-scopes first. Use internal Server-to-Server OAuth for only the approved account, not an invented public app. No Zoom credentials in Next.js, localStorage or Git.
2. Validate event subscription availability for the exact `webinar.participant_joined/left` variants and test real webhook payloads. Use Zoom webhook endpoint validation and signature/timestamp verification before accepting events.
3. Ack verified webhook quickly **only after durable receipt**, with dedup key and transactionally enqueued work; do not perform expensive Zoom API calls on the HTTP webhook path.
4. Treat live event data as **PROVISIONAL**. At webinar end fetch participant reports; handle recurring webinar scheduled ID vs occurrence UUID and repeated join/leave events. Merge intervals and raise identity mismatches for manual review.
5. Match students through stable academy identity and authorized Zoom registration identifiers. Free-text display name by itself does not justify automatic matching or absent/present decisions.
6. Account for report latency, API pagination, rate limits, errors, backoff and interrupted streams. All worker handlers are safe for at-least-once delivery and out-of-order events.
7. Native Zoom REST does **not** establish a per-student attendee emoji feed. Meeting SDK webinar attendee emoji reactions are described as anonymous. Zoom Apps SDK identity is a research hypothesis, not a production requirement. Use optional LUMA portal reactions for attributable metrics.
8. Zoom can support webinar poll setup and report retrieval, **not** a documented standard REST operation to auto-launch an in-Zoom native poll at arbitrary random times. Custom portal question/pulse is distinct.
9. Attendance time ≠ attention. A missing webhook is not proof of absence. Every correction has reason and audit entry.
10. Build and record a 3-student Zoom sandbox proof of concept (including reconnect, renamed guest and invalid signature) before go-live decisions.

## As built (003B, verified against fixtures only)
- `POST /api/v1/zoom/webhooks`, **off by default**: it answers 404 until `LUMA_ZOOM_WEBHOOK_SECRET_TOKEN` and `LUMA_ZOOM_WEBHOOK_ACADEMY_ID` are set. One academy per deployment until 003A adds per-academy credentials.
- Verification follows Zoom's documented scheme (checked on 2026-11-11 at https://developers.zoom.us/docs/api/webhooks/): HMAC-SHA256 with the webhook secret token over `v0:<x-zm-request-timestamp>:<raw body>`, compared in constant time with `x-zm-signature` (`v0=<hex>`). The timestamp must be within 5 minutes either way; Zoom documents no window, so this is our choice and must be checked against how Zoom stamps retried deliveries (retries come 5, 20 and 60 minutes later).
- `endpoint.url_validation` is answered with `{plainToken, encryptedToken = hex HMAC-SHA256(secret, plainToken)}`. Unlike Zoom's sample code, the request must carry a valid signature, and a `plainToken` containing `:` is refused, so the endpoint can never be used to sign an attacker's `v0:<timestamp>:<body>` string.
- Stored events: `webinar.started`, `webinar.ended`, `webinar.participant_joined`, `webinar.participant_left`. Other verified types are acknowledged and dropped.
- The receipt is committed before any 2xx. Zoom documents no event id, so a redelivery is recognised by the SHA-256 of the exact body and acknowledged without a second row; later processing must still be idempotent. A storage failure answers 503 (Zoom retries 5xx, never 4xx).
- Limits: JSON content type only, 64 KiB body cap read before verification. Logs hold event type and outcome only; payloads with names and emails are never logged. Payload retention remains an academy decision (OPEN-QUESTIONS P0).
- Not yet done: processing receipts (003C), occurrence mapping and identity resolution (003D), real-account proof (003A).

## As built (003C, processing)
- A poller in the API process (`LUMA_ZOOM_PROCESSING_ENABLED`, default on, every 5 s) claims due receipts with `FOR UPDATE SKIP LOCKED`, so several instances can run at once; each receipt is handled and marked in one transaction, so a crashed worker leaves it due again.
- Handler failures are undone with a JDBC savepoint while the claim is kept, then recorded: retry after 30 s doubling to 1 h (ten attempts over about 3 hours), or `DEAD` at once for malformed payloads and unknown types. Stored error text is the exception type or our own fixed message, never payload content.
- Owners and administrators list receipts by status (`GET /api/v1/zoom/receipts`, no payloads) and requeue dead letters with a reason that is written to the audit trail (`POST /api/v1/zoom/receipts/{id}/replay`).
- The only handler so far writes `zoom_occurrence_events` (webinar ID, occurrence UUID, event type, Zoom event time), one row per receipt, read in event-time order so late or out-of-order deliveries are harmless. It reads only `payload.object.id` and `payload.object.uuid`, which Zoom's event reference lists as required strings (checked 2026-11-11). Participant fields are documented there too but stay unparsed until a real test webinar confirms them.

## As built (003D part 1, occurrence mapping)
- A class session may carry a Zoom webinar ID (`zoomWebinarId`; spaces and hyphens as Zoom displays them are removed; digits, 9 to 20 long, is our assumption to confirm in 003A).
- When the worker files an event, the occurrence is linked automatically only if exactly one SCHEDULED class on that webinar ID has a time, widened by 60 minutes on both sides, that contains the event. Recurring webinars (one ID, many days) and restarted webinars (new occurrence UUID, same class) link correctly. No match or several matches leave it for review; cancelled classes and other academies' classes never match.
- Owners and administrators review occurrences (`GET /api/v1/zoom/occurrences?decided=false`), record a decision with a reason (`POST /api/v1/zoom/occurrence-links`, link or `sessionId: null` for "not a class"), and re-run matching after adding webinar IDs (`POST /api/v1/zoom/occurrences/auto-link`). Manual decisions are audited and never changed automatically.
- Still open: matching participants to students (registrant and participant fields need a real test webinar), and attendance.

## As built (003D part 2, participant identity)
- Field shapes come from the first real test webinar (13 Nov 2026). The handler copies, per joined/left event: the visit
  (`participant_uuid`, else `user_id`; both new on every rejoin), `registrant_id`, the email (lower-cased), the display name,
  and whether it is the host (`participant.id` equals `object.host_id`). IP addresses are not copied. Missing participant
  fields are stored as unknown instead of failing the event. V19 fills the same fields for events processed earlier.
- A person in an occurrence is identified by email, never by display name; a visitor without email is listed alone and
  cannot be matched. Matching order: an administrator's decision for the email (a student, or "not a student"; applies to
  every webinar, audited as `ZOOM_IDENTITY_LINKED` / `ZOOM_IDENTITY_MARKED_NOT_STUDENT`), then a student's LUMA sign-in
  email. The host is never matched.
- `GET /api/v1/zoom/participants?occurrence=` lists people with visits, open visits (no leave yet), first/last seen,
  the match and, when the occurrence is linked to a class, whether the student is expected there.
  `GET /api/v1/zoom/participants/unmatched` lists emails waiting for review; `GET|POST /api/v1/zoom/identity-links` lists
  and records decisions. Owners and administrators only. This is provisional live data, not attendance (004A).
- Receipts are stored without participant IP addresses, location, device, network type or MAC address (PRIV-1, V24 for older rows);
  a decision can be replaced but not removed; matching by `registrant_id` against Zoom's registrant list is not used,
  because email already identifies registrants and the registrant list needs an API call per webinar.

## First validation experiment
Collect account capabilities, real Zoom webinar occurrence identifiers, signed joined/left webhook samples (redacted), participant report result, mapping inputs, and three student joins/rejoins. Confirm the events' order/timestamps, registration identifiers and report accuracy. Only then finalize DTOs and integration tests.

Full research citations: [`docs/research/zoom-feasibility-full.md`](../research/zoom-feasibility-full.md) and [`docs/architecture/full-production-blueprint.md`](../architecture/full-production-blueprint.md).

## As built (004A/004B, attendance facts and Zoom's report)
- `GET /api/v1/class-sessions/{id}/attendance` reports facts per student (time connected within the class, rejoins merged;
  first join and lateness; visits; visits without a leave), never present/absent (rule 9; threshold is OPEN-QUESTIONS P0).
- After a webinar linked to a class ends, a poller (`luma.zoom.reports.*`, every 5 minutes, 15 minutes after the end) fetches
  `GET /past_webinars/{uuid}/participants` outside any transaction and stores it, replacing earlier copies; failures retry
  from 15 minutes doubling to 8 hours (or Zoom's Retry-After), six attempts. A later failure never discards a stored report.
  Administrators can fetch one now (`POST /api/v1/zoom/reports`, audited). Rows keep only email, name, registrant ID,
  join/leave and duration. Where every linked occurrence has a report, attendance says RECONCILED.
- Field mapping of the report was confirmed against the real test webinar by `scripts/zoom-api-check.sh` (13 Nov: 5 join
  sessions by 4 people); storage, retry and replacement are tested against a fake Zoom server.

## As built (WID, 2026-11-18): Zoom owns schedules; actual runs create attendance classes

Academy decision (`docs/product/ZOOM-WEBINAR-ID-ONLY-ACCEPTANCE.md`). Verified with fixtures and stored webhooks in
Testcontainers; **not yet proven against the academy's real Zoom account** (needs the webinar read scope and the four
webinar event subscriptions on the existing app, and a test webinar run).

- **Monitoring:** `POST /api/v1/zoom/monitors {webinar}` takes only a webinar ID or join link (owners/admins, audited).
  LUMA calls Zoom's Get a Webinar (read-only) to prove the academy's account can read it and copies title, timezone,
  scheduled start and duration (null when Zoom has none, never invented), recurrence and occurrences into
  `zoom_webinar_monitors` (V29). It re-reads Zoom every 30 minutes and on "Refresh from Zoom"; a failed read keeps the
  last copy marked stale. "Stop monitoring" changes only LUMA. LUMA makes **no** Zoom write call anywhere.
- **Runs:** for a monitored webinar, every event (`webinar.started`, `webinar.ended`, `webinar.participant_joined`,
  `webinar.participant_left`) creates or updates the run of its occurrence UUID (`zoom_webinar_runs`, one per
  `(academy, occurrence_uuid)`), and the run's attendance class (`class_sessions.origin = 'ZOOM_RUN'`). The run starts at
  Zoom's `start_time` on `webinar.started` (else the event time); a run whose start was missed starts at its first event
  (`actual_started_at` stays null: not invented). `webinar.ended` sets the actual end. The class is retimed to the actual
  run, so attendance is bounded by the actual start and end, not the schedule; while live the class ends at the 12-hour
  class limit so nothing is cut off, and dashboards count only the time held so far.
- **Idempotency and order:** one advisory lock per occurrence; receipts are stored once; an ended run is never reopened;
  a late `webinar.started` sets the start without changing the state; runs of the same webinar ID on different days stay
  separate (different UUIDs).
- **Reconciliation:** ended runs get Zoom's participant report as before. A run whose `webinar.ended` never arrived is
  fetched once older than the class limit plus the report delay; the report closes it at the last leave.
- **Course association:** a run expects the students of the webinar's Academy class only when that class is certain: the
  Zoom title equals exactly one Academy `class_type` (case and spacing ignored) or an administrator chose it (audited),
  and the class is set up as a LUMA batch. Otherwise the run has no expected students: evidence is kept, nobody is marked
  absent. Students are matched by Zoom email/registration or an audited staff decision, never by display name.
- **Webhook allow-list:** a monitored webinar's events are stored even when `LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST` does
  not list it.
- **Unmonitored webinars** keep the older behaviour (link to a class staff scheduled in the ±60-minute window), so history
  is unchanged. Scheduling, editing, cancelling and importing classes answer 409 unless
  `LUMA_SESSIONS_MANUAL_SCHEDULING=true` (test setups only).
