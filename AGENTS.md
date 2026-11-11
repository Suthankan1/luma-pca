# AGENTS.md — Instructions for every coding agent

You are working on **LUMA for Physics Cube Academy**, an intended production system for real students. Treat privacy, access control, attendance integrity, and engineering evidence as non-negotiable.

## Read this first, in order
1. `README.md` — actual implemented state and commands.
2. `docs/ai/START-HERE.md` — priorities, agent workflow and no-hallucination rule.
3. `docs/product/PRD.md` — product scope; `docs/product/OPEN-QUESTIONS.md` for unresolved academy decisions.
4. `docs/architecture/overview.md` and `docs/architecture/adr/` — accepted design boundaries.
5. `docs/security/JWT-AUTH.md`, `docs/security/AUTHORIZATION-MATRIX.md` — before any user-facing endpoint.
6. `docs/integration/ZOOM-RULES.md` — before integration, webhooks, reactions or attendance logic.
7. `docs/ai/checkpoints/PROGRESS.md` — resume checkpoint; update after every finished slice.

## Mandatory engineering rules
- Do **not** claim implemented features exist merely because they appear in specifications, mockups, or demo HTML.
- Build one small vertical feature at a time. Preserve the modular monolith; do **not** introduce microservices, Kafka, GraphQL, Redis or AI surveillance without an approved ADR.
- Java 21 + Spring Boot 4.1.2; TypeScript + Next.js 16. Respect package boundaries. No module may access another module's internal database entities/repositories directly.
- Academy/course/batch authorization must be enforced server-side on **each resource operation**. A student may only access own private data. New endpoints are deny-by-default until authorization tests pass.
- First-party JWTs require asymmetric signing (RS256/ES256), short-lived access tokens, refresh rotation, revocation family detection, secure session cookies and proper CSRF protection in the BFF. **Never store access/refresh tokens in browser localStorage.** Never hardcode signing keys.
- Zoom uses **separate** S2S OAuth credentials. Verify webhook signatures/timestamps, persist an idempotent receipt before acknowledging, retry safely, and reconcile after the webinar. Join/leave events are provisional only.
- Never use native Zoom attendee emoji identity unless verified on the actual licensed environment. The custom portal tracks its own reactions, not Zoom native reactions.
- Distinguish fictional demo data from actual data. Avoid attendance-as-attention and unconsented student ranking.
- SQL migrations are append-only after release; use Flyway and Testcontainers. Never edit an applied production migration.
- Validate errors, log safely without passwords/tokens/PII, and test negative paths, concurrency and repeated events.
- Do not push directly to GitHub unless the user explicitly asks. Do not auto-deploy, provision AWS, or use real credentials.

## Completion criteria for every slice
State changed files, important decisions, tests actually run/passed, untested work, rollout and rollback concerns. Update `docs/ai/checkpoints/PROGRESS.md` and any affected diagrams/contracts. Avoid huge commits.

## Prototype source handling
`references/legacy-frontend-demo/` is a **visual and interaction reference**, not secure application code and not a source of truth. Port its visual design gradually to Next.js, replacing simulated API logic with typed services.
