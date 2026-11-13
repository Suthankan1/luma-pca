# Proposed RAG API contracts (not implemented)

**Version:** proposal v0.1. Paths are under existing `/api/v1`; the full illustrative OpenAPI fragment is in [`contracts/openapi/rag-v1.proposed.yaml`](../../contracts/openapi/rag-v1.proposed.yaml). No endpoint below should be described as deployed until tests prove it.

## Student endpoints (authenticated; student identity from server)

| Endpoint | Behavior |
| --- | --- |
| `GET /api/v1/me/assistant/scopes` | Return ONLY enrolled course, batch and eligible class choices plus verified greeting name. Never include other student IDs or rosters |
| `POST /api/v1/me/assistant/questions` | Parse question, validate optional class scope, retrieve only permitted published sources, return cited answer, clarification or fallback |
| `POST /api/v1/me/assistant/answers/{answerId}/feedback` | Optional helpful/unhelpful feedback for caller's own prior answer; no free-text needed for MVP |

### Question request

```json
{
  "question": "When is the next revision class?",
  "scope": { "type": "BATCH", "id": "9d159018-04be-443b-9068-29cff1300994" }
}
```

The supplied UUID is a **selector**, not an authorization grant. `studentId`, `academyId`, `userName`, `role` and an arbitrary document filter are intentionally absent. When the scope is omitted, the backend proposes an authorized match. The server should cap question length (example proposed maximum: 1,000 UTF-8 characters; finalize with academy), reject blank questions, rate-limit and re-authorize each call.

### Answer cases

`mode` is one of `ANSWERED`, `CLARIFICATION`, `NO_SOURCE`, `ESCALATE`. The client **must** handle all modes.

**ANSWERED** example (illustrative, fictional timetable):

```json
{
  "answerId": "26dd8613-3097-457f-98da-a5402000c552",
  "mode": "ANSWERED",
  "answer": "The next revision class is on Saturday at 10:00 a.m. (Asia/Colombo), according to the published timetable. Please check for schedule updates.",
  "scope": { "type": "BATCH", "id": "9d159018-04be-443b-9068-29cff1300994" },
  "citations": [
    {
      "documentId": "5bb1957d-c3cd-467b-9566-6ed3d891ac35",
      "version": 2,
      "title": "Example Revision Timetable",
      "locator": "Page 1, Weekly lessons",
      "updatedAt": "2026-11-13T10:00:00Z",
      "sourceUrl": "/api/v1/me/assistant/sources/5bb1957d-c3cd-467b-9566-6ed3d891ac35?version=2"
    }
  ]
}
```

The `sourceUrl` is an **authenticated, re-authorized backend route**, not a direct S3 URL. Do not emit a citation if source scope or current publish state fails revalidation.

**CLARIFICATION** (student has two eligible classes):

```json
{
  "mode": "CLARIFICATION",
  "answer": "Which of your enrolled classes do you mean?",
  "choices": [
    { "type": "BATCH", "id": "9d159018-04be-443b-9068-29cff1300994", "label": "Revision — Batch A" },
    { "type": "BATCH", "id": "e8c98f94-aea1-4f20-af61-ed3eab20b4b6", "label": "Revision — Batch B" }
  ],
  "citations": []
}
```

**NO_SOURCE:** `{"mode":"NO_SOURCE","answer":"I could not confirm that from the academy's published information. Please contact the academy staff.","citations":[]}`. `ESCALATE` is similar, used for sensitive/person-specific questions. Never suggest a real phone number that staff has not configured.

### Source citation endpoint

`GET /api/v1/me/assistant/sources/{documentId}?version={n}` returns a safe, scoped citation preview or short-lived private download **only if** the caller's current enrollment permits that published version. It returns `404` for missing, cross-academy, unauthorized or no-longer-published documents; never expose raw storage keys.

## Staff endpoints (initially academy owner/admin only)

| Endpoint | Request/result |
| --- | --- |
| `POST /api/v1/knowledge/documents` | Multipart `file` and JSON `metadata`: title, language, exact scopes, validity range; 202 with document/version/job IDs; no auto-publish |
| `GET /api/v1/knowledge/documents` | Academy-scoped, paginated list; never raw storage URLs |
| `GET /api/v1/knowledge/documents/{id}` | Version/status/scope/preview and safe extraction warnings |
| `POST /api/v1/knowledge/documents/{id}/publish` | `{ "version": 2 }` plus If-Match optimistic precondition; 409 on stale or not READY; audit |
| `POST /api/v1/knowledge/documents/{id}/archive` | Archive and invalidate from retrieval immediately; audit and reason |

For each endpoint, backend checks current account role and academy from validated session, not client metadata. Future instructor upload requires separate ADR/policy and per-assigned-batch tests. All mutation calls use BFF origin/CSRF protections, size limits, `Cache-Control: no-store`, safe ProblemDetail errors and request IDs.

## Error and privacy rules

- `400` invalid content/question/scope format; `401` unauthenticated; `403` authenticated wrong role on a staff route; `404` foreign or unauthorized resource; `409` revision conflict; `413` oversized upload; `415` unsupported file; `422` extraction/empty source problems; `429` rate limit with `Retry-After` when appropriate; `503` provider failure mapped to staff fallback.
- No student name lookup endpoint, no arbitrary `documentId` search parameter, no chat-history list by student ID.
- Responses and cache headers must prevent public caching of personal/authorized results; do not log raw prompts, source passages or model payloads by default.
