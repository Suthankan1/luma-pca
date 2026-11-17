# START HERE — LUMA coding agents and team members

This repository holds the **complete, tested academy product** for one academy, not yet deployed (README status line,
`docs/delivery/KNOWN-LIMITS.md`). Changes are made safely, incrementally and measurably.

## Five-minute context
1. **Organization:** Physics Cube Academy uses Zoom Webinars, has multiple courses and batches; manual roll calls are the core pain.
2. **Product:** Staff desktop, student mobile portal, reliable connected-duration attendance, optional participation checks and source-attributed rankings.
3. **Chosen stack:** Next.js 16/TS, Java 21/Spring Boot 4.1.2 modular monolith, PostgreSQL/Flyway with a database queue; AWS as Terraform (not applied).
4. **Authentication:** First-party JWTs, rotating refresh sessions, sealed Next.js session cookies, TOTP MFA for every staff role, password change and reset; academy, batch-assignment and own-record checks on every route.
5. **Zoom:** S2S OAuth + signed webhooks + official reports after each webinar, proven with a real test webinar. Native attendee emoji identity and native poll auto-launch are not used.
6. **Design:** logo-inspired navy/blue/cyan/white, exact Figma link in README, old HTML demo under `references/` strictly for visual patterns.

## Grounding priority when sources conflict
`README.md`/`PROGRESS.md` *implementation state* > accepted ADRs *decisions* > `PRD.md` *requirements* > full blueprint *proposed plan* > archived legacy demo *fictional UI*. Do not mistake a backlog item for completed code. Ask the user only when P0 business decisions are blocking; otherwise build smallest safe skeleton with tests.

## First prompt for a new AI agent
See `PROMPT-LIBRARY.md` for copy-ready starter messages and `docs/delivery/IMPLEMENTATION-PLAN.md` for slices.

## Workflow
Identify one slice → inspect existing code/constraints → small plan → write tests → implement → run checks → update contracts/docs/checkpoint → give summary including actual commands, risks and next slice. Prefer PRs for reviewed changes and never push to production without explicit user request.
