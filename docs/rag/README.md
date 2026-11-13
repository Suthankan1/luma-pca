# LUMA Class Information Assistant — RAG integration plan

**Status:** Proposed design package for review, **not implemented**. Physics Cube Academy has endorsed the suitability of the LUMA idea and prototype at the third meeting. That endorsement is not a production launch, privacy-policy, AI-provider, or budget sign-off.

## Purpose

Reduce repetitive staff WhatsApp enquiries about **academy-approved class information** by answering enrolled students from documents uploaded, reviewed, and published by academy staff. Keep WhatsApp/staff contact as the fallback for anything unverified, personal, urgent or outside the knowledge base. This is an **additional LUMA module** alongside the existing attendance and engagement platform, not a replacement system.

## The critical identity rule

The student signs in through LUMA. The backend resolves the student from the authenticated account (`JWT sub` -> account -> student -> current enrollments). The assistant may greet the student using their **verified profile name**, but a name typed into chat is **never** proof of identity or permission to retrieve a document. A student must not be able to change the target student ID, academy ID, course ID, or batch ID to access another person's information.

## Staff workflow

1. An authorized academy owner/administrator uploads a PDF, DOCX, TXT or Markdown class-information document (file formats and size limits remain a policy decision).
2. Staff specify the exact academy/course/batch/class scope, language, source owner, and effective dates. An ambiguous scope is rejected, never defaulted to “everyone.”
3. The backend scans and extracts text, previews it, chunks and indexes it, and marks the candidate version **READY**; failed documents remain unpublished.
4. Staff review the extracted text and citations and **publish** the reviewed version. Publishing a replacement atomically supersedes the previous published version.
5. Staff can correct and republish a timetable or archive outdated content, with audited actions and immediate retrieval invalidation.

## Student workflow

1. The student opens **Ask LUMA** while signed in; the portal displays their verified name and **only their enrolled** classes/batches.
2. The student selects a class, or asks a question and lets the system propose an **authorized** class choice. Multiple plausible classes trigger a clarification question.
3. The server enforces academy + active enrollment + document scope + published/effective version filters **before retrieval**; the model only receives permitted passages.
4. The answer includes a short response, named source document, page/section, last-updated date when known, and a route to staff if no reliable answer exists.
5. The student can report that the answer was not helpful; the academy can improve the knowledge base without collecting unneeded conversations.

## What it will not do

- No answers from unpublished, expired, cross-batch or staff-only files.
- No fabricated schedules, fees, exam results, private student records or personalised attendance claims from RAG.
- No training a model on student conversations by default; no web/Bing search as a substitute for internal approved sources.
- No claim that the old static HTML/Figma prototype is a secure or operational AI assistant.
- No production enablement before academy approval for AI vendor, data retention, privacy/consent, content ownership and UAT.

## Documents in this package

| File | Use |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | User stories, requirements, boundaries and academy decisions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Existing-stack integration, retrieval and publication flow |
| [DATA-MODEL.md](DATA-MODEL.md) | Proposed tables, invariants, indexes and lifecycle |
| [API-CONTRACT.md](API-CONTRACT.md) | Proposed protected REST endpoints and response cases |
| [SECURITY-AND-SAFETY.md](SECURITY-AND-SAFETY.md) | Cross-batch isolation, prompt injection, PII, audit controls |
| [IMPLEMENTATION-ROADMAP.md](IMPLEMENTATION-ROADMAP.md) | Small test-first development slices and rollout gates |
| [EVALUATION-AND-UAT.md](EVALUATION-AND-UAT.md) | Groundedness/security tests and a staff/student demo script |
| [AGENT-IMPLEMENTATION-PROMPT.md](AGENT-IMPLEMENTATION-PROMPT.md) | Reusable implementation instructions for AI coding assistants |
| [../architecture/adr/ADR-0007-scoped-class-assistant.md](../architecture/adr/ADR-0007-scoped-class-assistant.md) | Proposed architectural decision; **not accepted** yet |
| [../diagrams/rag-question-flow.mmd](../diagrams/rag-question-flow.mmd) | Editable secure question-routing sequence |
| [../../contracts/openapi/rag-v1.proposed.yaml](../../contracts/openapi/rag-v1.proposed.yaml) | Separate illustrative contract, not deployed endpoints |
| [../../contracts/examples/rag/](../../contracts/examples/rag/) | Synthetic knowledge, questions, and answer examples |

## Fit with the existing repository

The source of truth for what already runs is the root `README.md` and `docs/ai/checkpoints/PROGRESS.md`. Current stack: Next.js 16 BFF/HttpOnly session cookies, Java 21/Spring Boot 4.1.2 modular monolith, PostgreSQL 17/Flyway, student and enrollment records, server-enforced course/batch authorization. Reuse those components and their test infrastructure. The Figma design and legacy demo are **visual references**; the RAG screens shown in the separately shared prototype are likewise not production code.

**Next action:** Review [open decisions](REQUIREMENTS.md#academy-decisions-required-before-live-use), accept or revise the proposed ADR, then start **RAG-01** in a separate implementation PR. No runtime app, schema migration, or cloud infrastructure is changed in this planning PR.
