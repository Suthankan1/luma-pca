# Proposed RAG architecture in the existing LUMA application

**Status:** Design proposal; no production components are added by this PR. Preserve the Java/Spring modular monolith, Next.js BFF and PostgreSQL/Flyway conventions.

## Boundaries

| Existing boundary | Extension |
| --- | --- |
| Next.js student portal | Add an authenticated **Ask LUMA** page with verified greeting, enrolled-scope selector, concise cited answers, feedback and staff fallback |
| Next.js staff portal | Add **Knowledge base** page: upload -> processing/preview -> scoped publish/replace/archive |
| Next.js BFF | Server-side authenticated API calls with encrypted HttpOnly sessions, origin/CSRF checks for mutations; no model key or JWT in browser JS |
| Spring Boot modular monolith | Add `knowledge` module (file/version/scope/ingestion) and `assistant` module (authorization-aware retrieval/orchestration); use existing `students`, `enrollments`, `batches`, `sessions`, `identity`, `audit` services through public interfaces |
| PostgreSQL 17 | Add versioned metadata/chunks/audit-compatible facts through **new** Flyway migrations; optionally add `pgvector` only after actual database support and ops are verified |
| Object storage | Private malware-scanned source files (local encrypted/dev fixture adapter; private S3 planned after approved infrastructure). Only backend-generated authorized downloads |
| Model provider | Server-to-server embedding + answer generation adapter; evaluate contracted provider separately. **Bing search or consumer Copilot web UI are not an approved internal-document RAG API** |

## Query routing and security order

1. Next BFF forwards the authenticated request; backend validates JWT/session, account status, academy and student role.
2. Resolve account UUID -> linked student UUID and current active enrollments **from database**, not names, chat text or client-supplied `studentId`.
3. Build the **allowed scope set**: academy-approved general sources plus courses/batches of active enrollments; for `CLASS_SESSION`, membership through `session_batches`; account for multiple batches and shared sessions.
4. Validate optional explicit scope against the allowlist. If absent, classify/rank candidate intents **only among allowed scopes**. Ask a clarification if multiple candidates remain plausible. A provided class name is a hint, not permission.
5. Query the index with mandatory `academy_id`, approved visibility, `PUBLISHED` version, effective date and authorized scope predicates **inside the database/retrieval filter**. Prefer hybrid full-text + vector retrieval, with parameterized access predicates. Recheck every returned chunk's linked document/version and scope before model use.
6. Assemble a limited context of authorized passages with stable citation IDs. Instruct the answer model to treat passages as **untrusted data**, never instructions. Generate a concise answer grounded in context; require valid citations.
7. Validate output citations against the retrieved authorized set, and apply rules for uncertain/conflicting answers. If validation fails, return `NO_SOURCE` or `ESCALATE`—not a fabricated answer.
8. Emit minimized operational metrics (request ID, answer outcome, latency, document-version IDs, **not** plain question or document text by default). Honor deletion/retention rules.

**Hard rule:** **Never** retrieve broadly and filter after the LLM has seen foreign chunks. Similarity scores or prompt text cannot override SQL permissions.

## Two kinds of facts

- **Scheduled class facts:** Actual start/end and cancellation status should eventually come from LUMA `class_sessions`, scoped to the student's enrollments. This is structured, current and more authoritative than a stale PDF; the integration slice must call existing public session services. If not available, answer only from current approved documents with a visible source timestamp.
- **Narrative class information:** Topics, class description, syllabus, instructions, term notices and policy FAQ come from reviewed uploaded documents via RAG.

When both disagree, do **not** silently choose. Policy proposal: structured schedule is authoritative for date/time; staff documents remain authoritative for narrative information. Present conflicts to authorized staff for correction, and do not cite a known-stale schedule as current.

## Ingestion pipeline

1. Staff upload with explicit target scopes, visibility, language, effective date and logical document name; validate MIME **by content**, extension, size and ownership.
2. Put file in quarantined private storage; malware-scan, reject encrypted/corrupt/active-content PDFs or unparseable formats and apply time/page limits. Remove EXIF/embedded tracking and never execute macros.
3. Extract text with page/section provenance. Normalize, detect empty/OCR-only pages and generate a **staff-visible text preview**; OCR remains a later scoped capability.
4. Split semantically with overlap; attach `academy_id`, source version, scope, page/section, language, effective dates and content digest to each chunk.
5. Generate embeddings using a versioned provider/model; index full-text and vector representations (or a verified equivalent) within tenant-scoped backend storage. Limit retry count and store safe processing status without leaking text to logs.
6. Staff reviews READY version and publishes atomically; archive previous published version. Re-ingestion failure leaves the prior published version working. A publish/unpublish/withdrawal must invalidate retrieval caches promptly, preferably by DB membership checks on each request.

## Classification and dialogue

Use deterministic domain information *first*: a requested `classSessionId` or `batchId` must be an allowed live resource; the student’s verified enrollments provide candidate scopes. Intent parsing can extract a course title, topic or date to **rank** these candidates, but never invent an identifier. If the student belongs to two batches both called "Revision", return `CLARIFICATION` listing only authorized options. Do not use the student's name as a search index key unless the academy explicitly stores non-sensitive personalization, and do not ask the student to type their name again.

There is no need for agent loops or tools capable of mutation in the MVP. One request -> constrained retrieval -> validated answer or fallback. Maintain only short, server-side conversation context if approved; re-authorize **on every turn**, even within an existing conversation.

## AI provider and cost controls

- Define server interfaces `EmbeddingClient` and `GroundedAnswerClient`; choose a contract-backed provider after privacy, latency, supported models and data-use terms are reviewed. If using Microsoft Azure offerings, configure approved Azure model/search services explicitly; Bing public search is optional research outside the private knowledge assistant.
- Keep credentials in secrets management; no API keys in Git, frontend code, logs or demo fixtures.
- Set maximum tokens, chunk count, uploaded text size, request timeouts, retries with budget, per-student query limits and outage fallback. Do not issue unbounded LLM calls.
- Measure retrieval recall, evidence correctness, abstention behavior, p95 latency and cost per query on a synthetic set *before* selecting provider or promising SLA.
- A full vector extension is not required in RAG-01; validate role authorization and exact-text retrieval first, then add semantic search in later slices.

## Observability and failure policy

- Record document processing state and safe error category. Staff can retry failed processing without duplicated published versions.
- On provider outage, high latency, unindexed document, conflicting sources or permission failure, show a clear response and offer staff fallback. Never reveal internal exception details or unauthorized source titles.
- Feature flag starts **disabled**; production enabling requires UAT, academy content review, privacy agreement, budgets and monitoring. Disable the assistant without breaking attendance/Zoom modules.

See [sequence](../diagrams/rag-question-flow.mmd), [data model](DATA-MODEL.md), [security](SECURITY-AND-SAFETY.md), and the **proposed** [ADR](../architecture/adr/ADR-0007-scoped-class-assistant.md).
