# Safe implementation prompt — use one slice at a time

This file helps developers working with coding assistants such as GitHub Copilot, Cursor or an LLM provider. It does **not** authorize blind full-system generation or any external data sharing. Keep the repository's root `AGENTS.md` and `docs/ai/START-HERE.md` authoritative.

## Copy-ready starter prompt

> You are working in the `Suthankan1/luma-pca` repository. First read `AGENTS.md`, `README.md`, `docs/ai/START-HERE.md`, the latest tail of `docs/ai/checkpoints/PROGRESS.md`, `docs/security/JWT-AUTH.md`, `docs/security/AUTHORIZATION-MATRIX.md`, `docs/product/OPEN-QUESTIONS.md`, and `docs/rag/README.md` plus its linked design. Distinguish built code from proposals. The academy has approved the *suitability of the prototype/idea*, not a production AI deployment. Preserve Java 21/Spring Boot 4.1.2 modular monolith, Next.js 16 BFF/HttpOnly sessions, PostgreSQL/Flyway and cross-academy/batch authorization.
>
> Implement **only RAG-[slice ID]** from `docs/rag/IMPLEMENTATION-ROADMAP.md`. Start with a tiny plan, RED tests (including a negative cross-batch case), and minimal code. Derive student identity server-side from JWT `sub`; names typed into chat or request IDs never grant access. Do not retrieve or send unauthorized document text to an AI provider. Use synthetic class fixtures, never secrets/real student data. A returned answer must have validated citations from currently published, effective, permitted content; otherwise return a safe fallback. Keep external AI providers behind an interface and off by default until approved. After coding, run focused tests and relevant full checks; update contracts, diagrams and `docs/ai/checkpoints/PROGRESS.md` with commands/results that actually happened; open a reviewable PR. Do not claim tested, deployed, approved or merged without proof.

## First coding slice recommendation

Start with **RAG-01**, not provider integration. Build the `GET /api/v1/me/assistant/scopes` **read-only** backend behavior and focused PostgreSQL/security tests. It should return the verified student's own display name and authorized course/batch/class options, including shared-class deduplication, but no classmate names. A changed `studentId` in query/body must not influence data. Add Next BFF/portal wiring only after backend tests pass.

## Before asking AI to generate RAG code

1. Confirm the academy's P0 content authorization and AI data-processing policy.
2. Verify the currently implemented `Student`/`Enrollment`/`ClassSession` API and migration numbering on the target branch.
3. Confirm chosen AI provider's actual enterprise API and data terms. A consumer Bing/Copilot interface is not an automatically available internal-document RAG API.
4. Ensure no production keys, example passwords, real WhatsApp numbers or actual student records enter prompts, commits or fixtures.
5. Review the output as code; inspect auth, query filters, indexing, model context and citations manually.
