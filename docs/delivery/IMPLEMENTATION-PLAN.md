# Small, testable implementation roadmap

**Project status at ZIP creation (11 Nov 2026):** only the bootstrap and interval-calculation primitive exist. The academy has NOT signed off P0 licensing, student volumes, data policy or launch budget. No production date is committed.

| Slice | Acceptance / exit criterion | Status |
| --- | --- | --- |
| 000A Repo + docs + agents + diagrams | `verify_structure.py`, ZIP check | DONE in archive |
| 000B Backend/DB bootstrap | Boot start, Flyway V1, health+status, unit tests | DONE (PR #1) |
| 000C Frontend bootstrap | Next install/lint/build and 3 preview pages | DONE (PR #1) |
| 001A Auth schema + hashed-password domain | PostgreSQL migration + TCs; no public endpoint | DONE (V2 accounts/roles, Argon2id, Testcontainers) |
| 001B Secure invitation + activation | Student/staff role creation, expiry and validation tests | DONE at service layer (V3 invitations, single-use hashed tokens); HTTP activation route and token delivery TODO with 001C |
| 001C JWT login | RS256 signatures, issuer/audience, lockout, MFA admin path | DONE except MFA: login, token validation, key rotation, lockout, activation route. Owner/admin sign-in is blocked until MFA (new slice 001F) |
| 001D Refresh and BFF | Atomic refresh rotation, reuse revocation, HttpOnly cookie, CSRF and e2e | DONE: Next.js BFF cookies/origin checks (PR #2) plus backend refresh rotation, reuse revocation, logout and logout-all; verified together by `scripts/auth-stack-smoke.sh`. Browser-driven (Playwright) e2e still TODO |
| 001F Admin MFA | TOTP enrolment and verification for owner/admin; removes the sign-in block | TODO |
| 001E Scoped authorization | Cross-academy, course, batch and student IDOR tests | PARTIAL: cross-academy isolation and role denial enforced and tested for courses/batches (002A); course/batch/student scope needs 002B assignments and enrollments |
| 002A Course/batch domain | CRUD + uniqueness + academy authorization | DONE (API only, owner/admin; create/read/update, deactivate instead of delete). Staff UI TODO |
| 002B Students, enrollments and staff assignment | M:N enrollment and scoped staff management, import validation | TODO |
| 002C Sessions and shared-batch schedule | No duplicate class records for shared sessions | TODO |
| 003A Zoom account authorization & secrets | Real sandbox account, scopes and safe token management | BLOCKED pending academy |
| 003B Webhook verification/receipt | URL validation, signature + replay window, idempotent database receipt | TODO |
| 003C Outbox/worker | Retry, DLQ, dedup, out-of-order receipts | TODO |
| 003D Webinar occurrence identity and registration mapping | Real Zoom tests, stable student IDs, review unknown users | TODO |
| 004A Provisional attendance timeline | Union/clip durations, SSE, late/missing and stale event handling | TODO |
| 004B Official report reconciliation | Correct outcome vs golden dataset, audit finalization | TODO |
| 004C Correction workflows + student history | Reasons, permissions, audit and appeal flow | TODO |
| 005A Attendance CSV exports | Scoped, tested private outputs | TODO |
| 006A Source-attributed engagement | Q&A/poll where available, portal reactions with consent | TODO |
| 006B Portal pulse checks | Instructor-controlled learning questions, timing, abuse controls | TODO |
| 006C Top 10/100 analytics | Valid source provenance, fair normalization and opt-in | TODO |
| 007A Reliability + ops | RDS/S3/SQS/alerts, load and restore tests, zero secrets in logs | TODO |
| 007B Academy production pilot | Approved Zoom proof, UAT, sign-off, reversible rollout | TODO |

**Suggested next small slice:** `001F` admin MFA, because every staff route built so far is only reachable by owner/admin accounts, which cannot sign in without the development override. Then `002B` students, enrollments and staff assignments, which unlock batch-scoped access for instructors. Implement each as one small test-backed commit and update `docs/ai/checkpoints/PROGRESS.md`.
