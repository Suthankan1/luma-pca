# Zoom integration — non-negotiable feasibility and reliability rules

**Status:** design only. No real Zoom token, webhook or registered student proof-of-concept exists in this repository.

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

## First validation experiment
Collect account capabilities, real Zoom webinar occurrence identifiers, signed joined/left webhook samples (redacted), participant report result, mapping inputs, and three student joins/rejoins. Confirm the events' order/timestamps, registration identifiers and report accuracy. Only then finalize DTOs and integration tests.

Full research citations: [`docs/research/zoom-feasibility-full.md`](../research/zoom-feasibility-full.md) and [`docs/architecture/full-production-blueprint.md`](../architecture/full-production-blueprint.md).
