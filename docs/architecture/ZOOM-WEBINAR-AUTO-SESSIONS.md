# LUMA architecture: Zoom-owned metadata, webhook-created attendance runs

**Status:** Implemented on branch `feat/webinar-id-auto-tracking` (see `docs/integration/ZOOM-RULES.md`, "As built (WID)"); verified with fixtures and stored webhooks, not yet with the academy's real Zoom account.

## What exists and what must change

Existing LUMA already has signed Zoom webinar webhooks, a persistent deduplicating inbox/worker, per-occurrence UUID evidence, participant visits and identity matching, post-webinar Zoom reports, scoped attendance APIs and dashboards. **Reuse these components.** `docs/integration/ZOOM-RULES.md` documents current matching of a webinar occurrence to an existing **SCHEDULED** `class_sessions` row within a time window; that is the key incompatibility. The current staff `SessionForms` and weekly-series flows also ask for title and timing, contrary to the new Academy instruction.

Inspect implementation, migrations and tests before choosing a migration strategy. Known touchpoints to inspect include:

- `backend/src/main/java/com/physicscube/luma/zoom/` (webhook, occurrence linking, report and lookup services)
- `backend/src/main/java/com/physicscube/luma/attendance/` and `/sessions/` (duration clipping, finalization, class/session invariants)
- `backend/src/main/java/com/physicscube/luma/academy/` (read-only Academy course/roster adapter; verify actual package path)
- `frontend/src/components/staff/SessionForms.tsx`, `frontend/src/app/staff/sessions/actions.ts`, `/staff/zoom/`, `/staff/zoom/import/`, staff and student attendance dashboards
- `docs/architecture/overview.md`, `docs/integration/ZOOM-RULES.md`, `docs/integration/ACADEMY-PAYMENTS-ROSTER.md`, `docs/ai/checkpoints/PROGRESS.md`

## Identity and state model

**Webinar binding:** Academy ID, Zoom owner/host account ID, normalized Zoom webinar ID, read-only cached metadata + version/fetched timestamp, local monitor enabled/disabled state and audit metadata. Prefer one active binding per `(academy, zoom_account, webinar_id)` unless an explicit business rule permits shared bindings; enforce uniqueness in the LUMA database.

**Run:** Each actual start creates/updates one row keyed by `(academy, zoom_account, webinar_uuid)` with webinar ID, `actual_started_at`, `actual_ended_at` (nullable), `status`, and a nullable course/batch association. Do not use scheduled date/time as its identity or insert a run merely because a webinar was scheduled. Store `scheduled_at` as separately fetched **display-only** metadata when present, never overwrite actual timestamps.

**States:** `DETECTED`/`LIVE`/`ENDED`/`REPORT_PENDING`/`FINALIZED`/`NEEDS_REVIEW` are conceptual; reuse the existing persistence states wherever possible and add only what is missing. An unknown event ordering must not move a finalized run backward. Processing is idempotent and transactionally durable.

**Visits and roster:** Zoom participant join/leave evidence belongs to the run UUID. Preserve rejoin visits. Resolve student identity to Academy `pcaid` using trusted Zoom registration/email and existing audited mapping; keep unmatched/ambiguous visitors as unresolved. Academy course membership comes exclusively from the existing read-only `payment`/`student` adapter. Do not join on free-text names or copy financial columns.

## Operational data flow

1. Authorized staff submits webinar ID/link. Server extracts numeric Webinar ID from validated supported formats; verifies it with Zoom `GET /webinars/{id}` using existing S2S OAuth; persists binding only in LUMA. It may show the name/time fetched from Zoom but never edit Zoom.
2. Read-only Zoom detail/list API populates schedule display and refreshes changes (on page load and bounded background refresh; cache with freshness and failure state). Recurrence must be represented from Zoom occurrence metadata; no LUMA weekly schedule is generated. No Zoom write scope required.
3. Zoom sends `webinar.started`. Existing ingress verifies raw-body signature/timestamp and URL challenge; inbox persists before acknowledging. Worker filters to monitored ID/account, obtains `object.uuid`, creates/upserts the run by UUID and actual event time. Never wait for scheduled start, and do not assume a LUMA scheduled row exists.
4. Joined/left events add/update visits under the UUID. If events arrive before `started`, create a **recoverable provisional run** with unknown actual start until a trustworthy start event or official Zoom details arrive; do not fabricate timestamps. Deduplicate and tolerate out-of-order/replayed events.
5. The staff live screen reads the stored run, its mapped Academy roster and visits. UI refreshes periodically using the existing mechanism or SSE if justified by tests. Display `LIVE` from run evidence, not schedule. Connected duration is interval union clipped to actual known run boundaries (and `now` only while live), not scheduled class boundaries.
6. `webinar.ended` sets actual end and queues the existing report workflow. Report results can arrive late; reconcile with the correct webinar **UUID**, merge multiple join sessions per identified student, and then apply the academy-approved versioned attendance rule. Missing evidence remains pending/review; absence is never inferred from missing webhook alone.
7. A subsequent start with the same numeric ID but a distinct UUID creates a **new** run. History remains separately queryable. On startup/recovery, recover unprocessed receipts and reconcile stored active runs with official Zoom APIs where permitted; use bounded retries/backoff and honor rate limits.

## Crucial legacy migration decisions

- Existing `class_sessions` persistence and finalization may require non-null scheduled times or schedule-dependent auto-linking. Implement additive Flyway migrations, preserve history and audit, and adapt the relevant services to represent event-created unscheduled runs. **Never edit applied migrations.** The solution may create a dedicated webinar-run aggregate and project into existing attendance APIs; prefer a minimal compatible extension after inspecting the code.
- Existing attendance percentages and status may be clipped to scheduled class bounds. Change the eligible window for this mode to **actual Zoom run start/end**, preserving historical rules for old sessions until intentionally migrated. Show both scheduled and actual times distinctly in reports when Zoom supplies both.
- Remove staff-facing schedule/create/edit/cancel forms and weekly-series controls only after the read-only webinar board + run-driven tracking have equivalent tested workflows. Do not remove old database records, historic reports or audit evidence.
- `GET /api/v1/attendance/dashboard` and student self-serve views must include event-created runs; keep object-level authorization and absence review semantics.
- An entered Webinar ID alone may not identify an Academy course if Zoom topic differs from `payment.class_type`. Auto-link only when **unique and provable** (e.g. exact normalized match or an existing approved binding). Otherwise store the webinar, show a visible unresolved course association, and do not compute the roster/absent counts or auto-finalize attendance until the Academy authorizes a mapping. Do not invent a required form field or secretly guess.
- Reuse existing Academy read-only datasource (`public.payment`, `public.student` with limited columns); preserve separate LUMA/Flyway datasource and no access to Academy attendance data.

## Security and failure containment

- Verify authorized Zoom account ownership for every entered ID and incoming event; reject foreign accounts and unsupported links. Use only existing Zoom app permissions, secret management and read APIs.
- Signature/timestamp verification, maximum request body size, durable receipt before 2xx, idempotent dedup, retry/DLQ, no PII in logs, no Zoom secrets in browser or Git.
- Owner/admin-only binding mutation; course/batch-scoped staff and own-attendance-only student reads; no username/role bypass. Retain existing sign-in and consent gates.
- If Zoom API/webhook is unavailable, do not display a stale schedule as live. Surface `Last synced` and an explicit unknown/stale state. Recover from receipts and official reports; do not double-count or fabricate absences.
- Do not initiate Zoom webinars or change their schedule via API. Do not provision AWS or perform a production deployment without separate approval.

## References

- Zoom Get a Webinar (`GET /webinars/{webinarId}`) and List Webinars (`GET /users/{userId}/webinars`): https://developers.zoom.us/docs/api/meetings/
- Webinar start/end/participant event schemas: https://developers.zoom.us/docs/api/meetings/events/
- Webhook security and validation: https://developers.zoom.us/docs/api/webhooks/
- Meeting/webinar instance UUID behavior: https://developers.zoom.us/docs/api/using-zoom-apis/
