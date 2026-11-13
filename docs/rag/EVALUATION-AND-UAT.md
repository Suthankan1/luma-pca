# RAG evaluation and academy UAT

**Status:** Test plan only. Metrics below are **proposed gates**, not results or academy-approved SLAs. Do not test with actual student personal information until privacy decisions and sign-off are complete.

## Offline golden set

Use at least 30 synthetic, source-linked questions spanning:

- 10 answerable questions with **one** approved source (class times, topics, links, schedule changes).
- 5 answerable questions across multiple **authorized** documents needing correct source selection.
- 5 ambiguous questions for a student with multiple batches/classes; expect `CLARIFICATION` with authorized choices only.
- 5 unanswerable, contradictory, stale or out-of-scope questions; expect `NO_SOURCE`/`ESCALATE`, not hallucination.
- 5 hostile questions: cross-batch/foreign academy, guessed document ID, embedded "ignore instructions" prompt injection, student PII request, revoked enrollment.

Fixture questions and expected outcomes are provided in [`contracts/examples/rag/evaluation-cases.json`](../../contracts/examples/rag/evaluation-cases.json). They are demonstrative; expand to an academy-reviewed question set.

## Evaluation dimensions

| Dimension | How to judge | Proposed pre-pilot gate |
| --- | --- | --- |
| Authorization isolation | Automated student/batch/academy IDOR and direct source-link tests | **100% pass**, zero unauthorized chunks seen by model or student |
| Unsupported claims | Human reviewers compare each factual statement with a cited source | Zero invented class dates/times/fees in curated test set |
| Citation validity | Cite source document/version/page; validate every citation ID and current permission | **100%** of answered test cases have valid, authorized citations |
| Retrieval recall | For answerable questions, intended source appears in allowed top-k candidate set | Set threshold after initial baseline and tuning; record actual results |
| Correct abstention | Unsupported/conflicting sources result in safe fallback | **100%** of curated no-source and sensitive-data cases avoid fabricated answers |
| Latency/cost | Record p50/p95 of question API, tokens/query and cost/query under simulated peak | Set academy budget and load target after measuring on approved provider |
| Accessibility | Keyboard, mobile narrow viewport, screen-reader labels, supported languages | No blocking usability defects in agreed UAT devices |
| Content freshness | Publish correction, archive/expiry and enrollment withdrawal between queries | Next question never uses removed/unauthorized source |

## Automated test matrix

1. **Authorization:** No login, wrong role, disabled account, account not linked to student, cross-academy, sibling batch, instructor non-assignment, withdrawn enrollment, shared session. Test metadata endpoint, retrieval, source preview and feedback ownership separately.
2. **Ingestion:** MIME spoofing, malformed PDF, DOCX macro, password-protected document, huge pages, duplicated bytes, parser crash, worker replay, empty document, file name injection.
3. **Versioning:** Concurrent publish, stale If-Match, failed candidate must not replace active publication, archived/expired version exclusion, delayed model response after revocation.
4. **Prompt safety:** Student prompt asks model to search all batches; malicious document instructs system to leak staff files, fake citation IDs, contradictory schedules, external web claims. Ensure the backend never sends forbidden chunks to provider and never returns unauthorized citations.
5. **Model failure:** Timeout, malformed JSON, provider 429/500, network outage, empty results, no embeddings yet. Bounded retry and fallback without affecting attendance services.
6. **UI/BFF:** Only server-side session token; CSRF and upload size; accessible class picker, citations, missing content, processing, publish confirmation, loading/error; no PII in browser storage or client analytics.
7. **Observability:** Logs contain request IDs, outcomes and safe error codes, not raw question text, student emails, model prompts, source bodies or secrets.

## Five-step academy UAT walkthrough

1. Owner logs in; uploads a **fictional** class notice to Batch A as draft. Confirm a student cannot find it before publication.
2. Owner previews extracted text and publishes version 1. An enrolled Batch A student signs in, sees their verified name, asks about the notice and receives a correct cited answer.
3. A Batch B student asks the same question, attempts to change `batchId` and opens a copied citation URL; they cannot access Batch A content.
4. The Batch A student enrolled in two classes asks "when is revision?" and receives a clarification, chooses one of their classes, and sees the correct document. Asking an unlisted question yields staff fallback.
5. Owner replaces the timetable, then archives it. New questions show the replacement while published; after archive, the old answer/citation is inaccessible. Record academy feedback, corrections and approval decision.

## UAT sign-off record (to be completed, not prefilled)

| Item | Evidence / decision |
| --- | --- |
| Academy approver and date | **Pending** |
| Approved source types / permission matrix | **Pending** |
| Provider/privacy/retention and minors policy | **Pending** |
| Test dataset/results and unresolved defects | **Pending** |
| Escalation contact and staffed hours | **Pending** |
| Feature-flag pilot / rollback decision | **Pending** |

Approval of the earlier **idea and prototype** does not pre-fill or replace this implementation/UAT sign-off.
