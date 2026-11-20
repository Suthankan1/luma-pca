# Operator runbook — Zoom webhook problems

1. Confirm event receipt API TLS, ingress availability and Zoom endpoint validation status.
2. Check signature-verification failure count without recording token/PII in logs.
3. Inspect durable inbox timestamps and queue age; compare receipt count to accepted HTTP 2xx acknowledgements.
4. Check failed and dead-lettered receipts (Zoom review → Failed events) and worker errors. Stop replay before addressing root cause to avoid double counting.
5. Replay only verified persisted event receipts with operator authorization and a recorded audit reason.
6. Reconcile webinar participant reports for every affected occurrence; mark ambiguous attendance as REVIEW, not ABSENT.
7. Notify academy operations and record incident timeline/corrective action.

**Signals:** application logs `Rejected Zoom webhook: signature <MISSING|MALFORMED|STALE|MISMATCH>` (check clock sync and the configured secret token) and `Could not store Zoom webhook` (database trouble; Zoom will retry 5xx). Receipts awaiting processing (the database queue): `SELECT event_type, count(*) FROM zoom_webhook_receipts WHERE processing_status = 'RECEIVED' GROUP BY 1`. Never copy `payload` contents into tickets or chat: they contain participant names and emails.

**As built (003C):** dead letters are visible to staff at `GET /api/v1/zoom/receipts?status=DEAD` (type, attempts and error only). Fix the cause first, then requeue each one with `POST /api/v1/zoom/receipts/{id}/replay` and a reason (written to the audit trail; no personal data in it). Replaying is safe: each receipt produces at most one occurrence event. A backlog shows as many rows `WHERE processing_status = 'RECEIVED' AND next_attempt_at <= NOW()`.
