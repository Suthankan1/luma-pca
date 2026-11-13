# ADR-0007 — Scoped, document-grounded class information assistant

**Status:** Proposed (requires team review and academy P0 decisions)  
**Date:** 2026-11-13  
**Decision scope:** LUMA / Physics Cube Academy RAG extension

## Context

The academy found the team's prototype/idea suitable at the third meeting. Staff currently answer repeated student questions about classes through WhatsApp. A prototype demonstrates a potential document-grounded chatbot, but it is not secure production code. LUMA already has authenticated students, course/batch enrollments, sessions and a modular Java backend with Next.js BFF.

## Proposed decision

Add `knowledge` (staff-managed approved document versions/scopes/ingestion) and `assistant` (student question resolution, permission-aware retrieval and evidence-grounded responses) as internal modules of the **existing Spring Boot monolith**. Reuse PostgreSQL/Flyway and existing role/enrollment checks. Default to staff-reviewed **published** document versions, tenant and batch filters *inside retrieval*, signed-in-student personalization via verified account, cited answers and abstention/human handoff. Only owner/admin publishes at first. AI providers and vector indexes are replaceable adapters; exact vendor choice awaits P0 privacy/cost decisions.

## Alternatives considered

1. **Ask students to enter a name and search matching files:** rejected; names are non-unique and guessable, unsafe for private batch content.
2. **Send all academy files into one chatbot prompt or external general web/Bing search:** rejected; exposes private material and can hallucinate outdated timetables.
3. **New chatbot microservice/vector SaaS immediately:** deferred; adds deployment, secrets and authorization boundaries before volume/hosting justify it.
4. **Manually curated FAQ only:** useful fallback and possible first answer source, but cannot scale to changing notices/documents on its own.

## Consequences

- Every query, chunk, and citation needs current academy/course/batch/class permission and publication checks.
- Staff must own data quality, versioning, effective dates and approval; semantic search alone cannot determine factual freshness.
- Provider outages and lack of evidence must return a safe staff-contact route, with no claim that chatbot replaces all support.
- Ingestion, embeddings and Q&A have operating costs and third-party data exposure; academy must approve provider, retention and minors policy.
- Schema changes and live endpoint additions require focused integration/negative tests and separately reviewed PRs.

## Decision record

- Prototype/idea suitability: **reported approved by academy** (third meeting); no separate technical, legal or production sign-off is asserted.
- Implementation ADR acceptance: **pending**.
- Technical vendor, content visibility matrix, retention, fallback route and budget: **pending** (see `docs/rag/REQUIREMENTS.md`).
