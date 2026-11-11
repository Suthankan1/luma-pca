# Operator runbook — Zoom webhook problems (planned)

1. Confirm event receipt API TLS, ingress availability and Zoom endpoint validation status.
2. Check signature-verification failure count without recording token/PII in logs.
3. Inspect durable inbox timestamps and queue age; compare receipt count to accepted HTTP 2xx acknowledgements.
4. Check SQS retry/DLQ and worker idempotency errors. Stop replay before addressing root cause to avoid double counting.
5. Replay only verified persisted event receipts with operator authorization and a recorded audit reason.
6. Reconcile webinar participant reports for every affected occurrence; mark ambiguous attendance as REVIEW, not ABSENT.
7. Notify academy operations and record incident timeline/corrective action.

The endpoints/queues are **not implemented** in starter; this is a runbook template.
