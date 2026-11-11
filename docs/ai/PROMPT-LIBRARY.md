# Copy-ready prompts for coding agents

## Resume / inspect
> You are working in the `luma-pca` repository. Read `AGENTS.md`, `docs/ai/START-HERE.md`, `docs/ai/checkpoints/PROGRESS.md`, `docs/product/PRD.md` and the relevant ADR. Inspect the actual working tree and report what is implemented vs planned. Select the smallest next slice from `docs/delivery/IMPLEMENTATION-PLAN.md`, explain its acceptance tests and safety implications; don't make adjacent changes.

## Implement one slice
> Implement only Feature [ID and precise description]. Follow `AGENTS.md`, affected domain package AGENTS instructions and ADRs. Write or update unit and integration tests including security negative paths. Preserve course/batch isolation, tenant boundaries, JWT/BFF cookie security and Zoom receipts where relevant. Run focused + relevant full builds; report actual results and update the checkpoint. Avoid unverified claims or redesigning unrelated screens.

## Write a failing test first
> Read the targeted module and create exactly one focused RED test for [behavior], including edge cases and failure mode. Do not change production code yet. State expected failure and commands. Use Testcontainers for PostgreSQL persistence behavior; no mocking away key transaction boundaries.

## Plan auth correctly
> Before coding account login, read `docs/security/JWT-AUTH.md` and `AUTHORIZATION-MATRIX.md`. Break work into account schema, password hashing, invite/recovery, signed short-lived JWT, opaque refresh rotation, key management, Next BFF cookies/CSRF, and backend per-resource authorization. Do not put tokens in localStorage, bundle private keys or mark login ready without IDOR and revocation tests.

## Implement Zoom one safe piece
> Read `docs/integration/ZOOM-RULES.md` and the research blueprint. Verify official webhook signature and specific event payload with academy test account. Implement only signed webhook receipt persistence and replay validation; no live 'Present' decisions yet. Unit-test tampered signatures and integration-test idempotent receipt insertion.

## Frontend port from old demo
> Read `docs/design/DESIGN-SYSTEM.md` and review `references/legacy-frontend-demo/` as visual reference. Port only [one named UI screen] to Next.js 16 with accessible responsive components. Clearly label demo values as fictional until authenticated backend integration. Never copy the old browser-side fake auth or raw data into production.

## Architecture review
> Compare changed code against ADRs and security/privacy/Zoom rules. Report concrete violations with file paths, remediation and test coverage. Distinguish documented plan from running behavior. Don't propose microservices without measured demand.

## Release readiness
> Audit `docs/delivery/GO-LIVE-CHECKLIST.md`, current CI, migrations, auth, Zoom proof, incidents, backup restore, source-specific engagement claims and privacy controls. List blockers with owners. Never declare production ready while any critical gate is open.
