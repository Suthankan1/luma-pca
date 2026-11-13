# Proposed knowledge data model (no migration in this PR)

**Status:** Planning only. When implementing, add append-only Flyway migrations following the current highest deployed version; do not assume the next number is `V25` without checking `backend/src/main/resources/db/migration` on the implementation branch.

## Tables and key constraints

| Entity | Purpose and candidate columns | Critical constraints |
| --- | --- | --- |
| `knowledge_documents` | `id`, `academy_id`, `title`, `language`, `owner_account_id`, `created_at`, `archived_at` | One logical document; owner and academy match. Archive by state, not physical delete on first request |
| `knowledge_document_versions` | `id`, `document_id`, `version_no`, `storage_key`, `sha256`, `mime_type`, `source_filename`, `processing_state`, `publish_state`, `effective_from`, `effective_until`, `published_at`, `published_by`, `created_at` | `UNIQUE(document_id, version_no)`, `effective_until > effective_from` if set; only READY version may publish; one active published version per document (partial unique index or transaction-locked invariant) |
| `knowledge_document_scopes` | `version_id`, `scope_type` (`ACADEMY_GENERAL`, `COURSE`, `BATCH`, `CLASS_SESSION`), `scope_id` nullable for general, `visibility` | Explicit positive grants only; non-general IDs must resolve in **same academy**. Class membership follows existing `session_batches`. A version may have multiple validated scopes |
| `knowledge_chunks` | `id`, `version_id`, `ordinal`, `page_no`, `section`, `body_text`, `content_sha256`, `token_count`, `embedding_model`, `embedding_version`, `embedding`, text-search vector | `UNIQUE(version_id, ordinal)`; use compatible dimensions/index per embedding model; remove chunks only when safe by retention. Store no student PII in test fixtures |
| `knowledge_processing_jobs` | `id`, `version_id`, `state`, `attempts`, `next_attempt_at`, `error_code`, `created_at`, `updated_at` | One active job per version; `SKIP LOCKED` style claim/idempotence as per existing operations; bounded backoff/dead-letter review |
| `assistant_answer_events` (optional; minimized) | `id`, `academy_id`, `account_id` or pseudonymous actor key (if policy permits), `outcome`, `latency_ms`, `citation_version_ids`, `created_at`, `feedback` | Do **not** persist raw question/prompt/model output by default. Tenant-safe deletion and retention; access only for approved operational roles |

## Visibility and scope intersection

Recommended initial visibility has **one safe broad category**: `ACADEMY_GENERAL` (signed-in students of that academy, not internet-public). Private content uses explicit `COURSE`, `BATCH`, or `CLASS_SESSION` scope grants. No `ALL_STUDENTS` fallback on a missing association, no staff-only document reused through student routes. A separate staff-only document category may be added later, but student retrieval must never see it.

Authorization predicates are evaluated at query time from **current** enrollments/assignments, not a materialized snapshot that might lag a withdrawal. A version is retrievable only when:

```
request.account.active
AND account.academy_id = version.document.academy_id
AND version.processing_state = READY
AND version.publish_state = PUBLISHED
AND (effective_from IS NULL OR effective_from <= now)
AND (effective_until IS NULL OR now < effective_until)
AND (scope = ACADEMY_GENERAL OR scope intersects request's verified active enrollments)
```

If a user selects an explicit batch/course/class, also intersect the permitted document scopes with that selected context **without broadening to another batch**. One question may need multiple passages from a single authorized course; return citations only from the authorized retrieved versions. A shared class linked to several batches is a single class entity but membership can be established through any one valid linked batch.

## Publication transaction

1. Staff uploads a candidate version as `DRAFT` + `UPLOADED`; scope grants are validated and stored before indexing.
2. Worker moves `UPLOADED` -> `PROCESSING` -> `READY` (or `FAILED`, safe error only). File content is not queryable while draft, even if embeddings exist.
3. Authorized staff inspects preview and issues publish with optimistic version/If-Match precondition.
4. One transaction locks the logical document, confirms READY and academy scope, marks the previous published version `SUPERSEDED` and new version `PUBLISHED`, and adds an audit event.
5. Replacing, archiving or changing scope revokes old retrieval eligibility immediately; retrieval and citation validation recheck the authoritative version row. Delayed workers cannot re-publish a superseded candidate.

## Storage and recovery

- Database stores metadata and chunks; raw source files in **private**, non-public object storage with opaque keys and server-side encryption. Do not store public URLs, AWS keys or downloaded content in chat responses.
- Uploaded file hashes allow deduplication within one academy/logical document; hashes never authorize cross-academy reads.
- Track source version and extraction engine version so errors can be reproduced. Deletion/purge includes raw object, chunks, embeddings, answer-event metadata and backups under an approved schedule.
- Rebuilding embeddings after a model change runs in draft/staged index space and swaps only when complete; do not mix incomparable embedding vectors in one similarity index.
- Require PostgreSQL 17 Testcontainers coverage for foreign-key academy checks, uniqueness, expiration boundaries, concurrent publishes and partial unique published-version enforcement.

This is a relational proposal, **not** executable DDL. Implement types, indexes and migration sequencing one verified slice at a time.
