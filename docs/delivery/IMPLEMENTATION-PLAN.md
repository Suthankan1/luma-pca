# Small, testable implementation roadmap

**Project status at ZIP creation (11 Nov 2026):** only the bootstrap and interval-calculation primitive exist. The academy has NOT signed off P0 licensing, student volumes, data policy or launch budget. No production date is committed.

| Slice | Acceptance / exit criterion | Status |
| --- | --- | --- |
| 000A Repo + docs + agents + diagrams | `verify_structure.py`, ZIP check | DONE in archive |
| 000B Backend/DB bootstrap | Boot start, Flyway V1, health+status, unit tests | DONE (PR #1) |
| 000C Frontend bootstrap | Next install/lint/build and 3 preview pages | DONE (PR #1) |
| 001A Auth schema + hashed-password domain | PostgreSQL migration + TCs; no public endpoint | DONE (V2 accounts/roles, Argon2id, Testcontainers) |
| 001B Secure invitation + activation | Student/staff role creation, expiry and validation tests | DONE at service layer (V3 invitations, single-use hashed tokens); HTTP activation route and token delivery TODO with 001C |
| 001C JWT login | RS256 signatures, issuer/audience, lockout | DONE. Password sign-in, token validation, key rotation, lockout and invitation activation. See 001F for admin MFA. |
| 001D Refresh and BFF | Atomic refresh rotation, reuse revocation, HttpOnly cookie, CSRF and e2e | DONE: Next.js BFF cookies/origin checks (PR #2) plus backend refresh rotation, reuse revocation, logout and logout-all; verified together by `scripts/auth-stack-smoke.sh`. Browser-driven (Playwright) e2e still TODO |
| 001F Admin MFA | TOTP enrolment and verification for owner/admin; removes the sign-in block | DONE in the backend (TOTP, recovery codes, reset, lockout); the development override was removed. Browser MFA step is new slice 001G |
| 001G Browser MFA step | Next.js code entry, enrolment QR and recovery-code display; Playwright coverage | DONE: Next.js MFA challenge handling, enrollment QR code, manual secret display, TOTP and recovery-code verification, one-time recovery codes presentation and acknowledgment, unit tests, HTTP smoke tests, and Playwright e2e browser tests |
| 001E Scoped authorization | Cross-academy, course, batch and student IDOR tests | DONE for the routes that exist: cross-academy, batch-scope, student-ownership and role tests (002A, 002B). Extend with every new resource |
| 001H Staff account administration | Create staff, change roles, disable, issue invitations; escalation and last-owner rules; audit trail | DONE (API; staff screens in UI-3a) |
| 001I Browser account activation | Next.js page that redeems an invitation and sets a password | DONE: `/activate`, code read from the link fragment, Playwright and real-stack tests |
| 001J Password reset by email | Self-service reset link by SMTP; browser pages | DONE: API and SMTP sender (part 1); `/forgot-password` and `/reset-password` pages, real SMTP run through Mailpit in the real-stack test (part 2). Email provider still to be chosen by the academy |
| 001K Emailed invitations | Administrators email the activation link instead of copying it; shared email settings | DONE: `delivery: EMAIL` on the invitation API (link not returned), "Email invitation" button, real SMTP run for an instructor |
| 001L Per-visitor limits on public auth routes | 429 with Retry-After per client address, shared across API instances; trusted-proxy handling in API and web | DONE: PostgreSQL fixed-window counters, IPv6 per /64, forged X-Forwarded-For ignored from untrusted callers, real-stack run through the web tier |
| 001M First-owner operator command | Create an academy and its first owner without SQL; no HTTP route | DONE: `scripts/bootstrap-owner.sh` (print or email the link), refuses to replace an active owner, real-stack run now seeds through it |
| 002A Course/batch domain | CRUD + uniqueness + academy authorization | DONE (API only, owner/admin; create/read/update, deactivate instead of delete). Staff UI TODO |
| 002B Students, enrollments and staff assignment | M:N enrollment and scoped staff management, import validation | DONE: student records with linked accounts, batch enrollments, student own-record routes (part 1); staff assignments with batch-scoped read access for instructors and attendance officers (part 2). Bulk import and staff account administration TODO |
| 002C Sessions and shared-batch schedule | No duplicate class records for shared sessions | DONE: class sessions shared by up to 20 batches, expected students listed once per session, batch-scoped staff reads. Attendance uniqueness itself arrives with 004A |
| UI-1 Staff screens: shell, courses and batches | Figma-based staff layout, server-side API client, Server Actions with inline errors, Playwright desktop and mobile | DONE; also verified on the real stack by `scripts/staff-ui-real-stack.sh` (UI-2) |
| UI-2 Staff screens: students and enrollments | Student list with paging, create with invitation link, batch roster | DONE: list, detail, invitation link, enrol/withdraw/re-enrol, rosters for managers and assigned staff; real-stack browser run |
| UI-3a Staff screens: staff accounts and batch assignments | Team list and record (roles, invitation, disable) within the role rules; assign and remove staff on the batch page | DONE; instructor flow verified on the real stack |
| UI-3b Staff screens: class sessions | Schedule, edit and cancel sessions shared by batches | DONE: fortnight list with batch filter, schedule/edit/cancel/restore, expected students; real-stack run checks stored UTC times |
| UI-3c Staff screens: Zoom review | Dead-lettered receipts with replay; occurrence review (link, not a class, auto-link) | DONE: decisions with recorded reasons, automatic linking on demand, replay; real-stack run against seeded Zoom data |
| 003A Zoom account authorization & secrets | Real sandbox account, scopes and safe token management | BLOCKED pending academy |
| 003B Webhook verification/receipt | URL validation, signature + replay window, idempotent database receipt | DONE against fixtures: signature and timestamp checks, URL validation, idempotent durable receipt, off by default. Real Zoom delivery unproven until 003A |
| 003C Outbox/worker | Retry, DLQ, dedup, out-of-order receipts | DONE with an in-database queue: multi-worker claiming, backoff, dead letter, audited replay, per-occurrence event log. SQS transport and separate worker deployment remain the production plan |
| 003D Webinar occurrence identity and registration mapping | Real Zoom tests, stable student IDs, review unknown users | PART 1 DONE: occurrence-to-class-session linking (automatic when unambiguous, audited manual decisions). Part 2, student identity from registrants and participants, needs a real test webinar (003A) |
| 004A Provisional attendance timeline | Union/clip durations, SSE, late/missing and stale event handling | TODO |
| 004B Official report reconciliation | Correct outcome vs golden dataset, audit finalization | TODO |
| 004C Correction workflows + student history | Reasons, permissions, audit and appeal flow | TODO |
| 005A Attendance CSV exports | Scoped, tested private outputs | TODO |
| 006A Source-attributed engagement | Q&A/poll where available, portal reactions with consent | TODO |
| 006B Portal pulse checks | Instructor-controlled learning questions, timing, abuse controls | TODO |
| 006C Top 10/100 analytics | Valid source provenance, fair normalization and opt-in | TODO |
| 007A Reliability + ops | RDS/S3/SQS/alerts, load and restore tests, zero secrets in logs | TODO |
| 007B Academy production pilot | Approved Zoom proof, UAT, sign-off, reversible rollout | TODO |

**Suggested next small slice:** every API area now has a staff screen. `003A` (a real Zoom test webinar with the academy's credentials) unblocks participant matching and attendance (`004A`); meanwhile, candidates are deployment infrastructure once the academy authorises cloud resources, the 1,500-user load test plan, or a reviewed owner-recovery procedure. The real-stack browser test runs in CI (workflow "Real-stack browser test") for every backend or frontend change.
