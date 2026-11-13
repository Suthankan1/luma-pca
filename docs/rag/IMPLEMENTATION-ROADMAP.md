# RAG delivery roadmap — small, test-first slices

**Status:** Planned; each slice should be its own reviewed implementation PR after the academy and team accept the [proposed ADR](../architecture/adr/ADR-0007-scoped-class-assistant.md). Preserve existing `docs/delivery/IMPLEMENTATION-PLAN.md` history; assign these feature IDs to this extension. No runtime work is done by the planning PR.

| Slice | Deliverable and smallest observable behavior | Required evidence before merge |
| --- | --- | --- |
| **RAG-00** — decisions | Approve source categories, uploader/publisher role, PII/privacy and AI provider contract, fallback channel | Academy decision record; amended PRD/permission matrix and ADR accepted (not automatically by this planning PR) |
| **RAG-01** — permission-safe scope catalog | `GET /api/v1/me/assistant/scopes` derives verified profile and eligible course/batch/class scopes from existing student/enrollment/session services; no LLM or file upload | Student with two enrollments; withdrawn student; same-academy other-batch; other-academy IDOR; no source identifiers from request; frontend smoke |
| **RAG-02** — draft document metadata | Owner/admin creates a **draft** scoped version and reads status; new Flyway tables, no extraction yet | PostgreSQL Testcontainers FK/academy constraints, invalid scope, duplicate/concurrent create, role denial, audit; other modules unchanged |
| **RAG-03** — safe upload, extract and preview | Upload PDF/DOCX/TXT/MD to quarantine; validate, scan, extract page/section text, show staff preview; no publication | MIME mismatch, encrypted/corrupt file, oversize, empty text, zip bomb/timeouts, source storage authorization, failure retries; no real PII fixture |
| **RAG-04** — review/publish/revoke | Reviewed READY version is published; replacement, expiry and archive take effect; server-only authorized text retrieval (exact-match first) | State-machine, two concurrent publish attempts, source version citations, revocation, staff roles, cross-tenant/batch tests |
| **RAG-05** — grounded assistant endpoint | Student asks; class selector/clarification, safe exact-text retrieval, `ANSWERED/NO_SOURCE/ESCALATE`, reference IDs validated; deterministic fake model first | End-to-end authenticated read, multi-batch ambiguity, prompt injection, abstention, cross-batch request, revoked source, source links; zero external provider secrets |
| **RAG-06** — semantic retrieval and provider | Adapter for approved embedding/LLM provider, hybrid query, vector index (if verified), rate limits, budgets and outage fallback | Synthetic gold QA suite, grounded answer/citation tests, unanswerable set, multilingual checks if approved, concurrency/load, cost and latency measured |
| **RAG-07** — integrated UI and UAT | Next.js staff knowledge screen, student Ask LUMA screen aligned to approved prototype; production-safe BFF and feedback; academy one-batch pilot behind flag | Desktop/mobile accessibility/Playwright, stale/error/loading states, signed academy UAT/privacy and explicit feature enablement |

## Implementation order and source hierarchy

In the first code slice, reuse `identity`, `students`, `enrollments`, `batches`, `sessions` through their existing service boundaries. Do not build a new auth table or a second student directory. Keep ingestion and assistant as internal packages of the existing Spring monolith, not a microservice. Use the existing PostgreSQL/Flyway/Testcontainers pattern and Next BFF cookie/session pattern. Refer to [architecture](ARCHITECTURE.md), [data model](DATA-MODEL.md) and [API contract](API-CONTRACT.md).

Structured `class_sessions` is the preferred future source for actual upcoming dates/times, after authorization; uploaded documents provide class descriptions and academy notices. Do not silently claim live schedule integration is implemented at RAG-05. API paths and schema names in this plan may be revised through tests/ADR before runtime addition.

## Feature flags / rollout

1. Default **off** for every academy. Keep background ingestion/index jobs off unless configured; no external AI call by default.
2. Synthetic local test, then signed-off staff preview, then one consenting test batch, then controlled expansion. Monitor fallback rate, correctness feedback, rate limits, latency, provider failures and cost.
3. Use a kill switch that returns a clear staff-contact fallback and leaves attendance/Zoom endpoints operating normally. Roll back UI/feature flag first; keep DB migrations append-only and reversible by forward cleanup rather than rewriting history.
4. After each finished slice, append factual evidence (commands/results/PR/commit if known) to `docs/ai/checkpoints/PROGRESS.md` and update relevant OpenAPI/test docs. Never mark a proposed endpoint implemented based on this roadmap.

## Suggested work split (not required team assignments)

- **Backend/security:** scoped student catalog, authorization, lifecycle, records, source citation endpoint.
- **Data/AI:** parser + indexing, provider adapter, grounded answer tests and evaluation dataset.
- **Frontend/UX:** staff upload/preview/publish and student ask/clarification/fallback using BFF.
- **QA/privacy:** permission-negative matrix, prompt-injection/adversarial dataset, UAT evidence and academy policy checklist.

Keep individual PRs reviewable and testable; do not bundle all slices into one AI-generated implementation.
