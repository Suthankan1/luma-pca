# Zoom integration — non-negotiable feasibility and reliability rules

**Status:** signed webhook receipt is implemented and tested against fixtures (003B). No real Zoom token, webhook delivery or registered student proof-of-concept exists yet.

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

## First validation experiment
Collect account capabilities, real Zoom webinar occurrence identifiers, signed joined/left webhook samples (redacted), participant report result, mapping inputs, and three student joins/rejoins. Confirm the events' order/timestamps, registration identifiers and report accuracy. Only then finalize DTOs and integration tests.

Full research citations: [`docs/research/zoom-feasibility-full.md`](../research/zoom-feasibility-full.md) and [`docs/architecture/full-production-blueprint.md`](../architecture/full-production-blueprint.md).
