# ADR-0003 — Durable Zoom webhook ingestion and reconciliation
**Status:** Accepted in architecture; not implemented · 2026-11-11
**Decision:** Validate authenticity and replay window; durably write raw receipt + outbox transaction; ack promptly. Process at least once with idempotent receipts, retry with bounded backoff and DLQ, and fetch post-webinar participant report to reconcile missing/out-of-order joins/leaves. Only after reconciliation may attendance move from PROVISIONAL/REVIEW to FINALIZED. Keep external Zoom occurrence identifiers distinct from scheduled webinar IDs.
**Not allowed:** Marking an absent student solely because a webhook didn't arrive; assuming native per-student emoji events; unverified automatic native poll launches.
**Consequences:** Need rate-limit-aware report processing, exception queue, manual correction audit and post-webinar verification test cases.
