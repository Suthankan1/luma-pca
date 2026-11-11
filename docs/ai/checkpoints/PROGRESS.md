# Persistent development checkpoint — LUMA

## Checkpoint 000A — 2026-11-11 (initial ZIP)

**Repository:** `Suthankan1/luma-pca` was inspected and found empty at bootstrap creation. The ZIP is a local deliverable; it has **not** been pushed to GitHub.

**Decided:** Next.js 16 / TypeScript frontend; Java 21 Spring Boot 4.1.2 modular monolith; PostgreSQL/Flyway; first-party signed JWT access + rotating opaque refresh sessions and Next secure BFF; multiple courses and batches from day one; Zoom S2S OAuth + webhooks and eventual official-report reconciliation; SQS/worker and AWS as production plan subject to costs.

**Included code:** Next.js home/staff/student preview pages using supplied academy logo and theme; Java Spring Boot app with public bootstrap status route, default-deny other API paths and local DB/health config; Flyway V1 academy/course tables; attendance union/clip domain primitive and JUnit tests; Docker Compose PostgreSQL. **Running status:** local builds/tests must be verified in each developer environment; see validation summary in root README if added.

**Included knowledge:** Full prior 2026-11-11 production architecture and feasibility research, PRD, user stories, unresolved questions, role matrix, JWT and Zoom rules, ADRs, Mermaid context/ERD/sequences/deployment, delivery plan, test strategy, runbooks, CI setup, prompts and agent instructions, legacy HTML demo as non-production visual reference.

**Explicitly not implemented:** real login/invitation/JWT signing/BFF sessions, full academic CRUD, student records, Zoom OAuth/webhook processing, attendance persistence/finalization, SQS worker, SSE, actual reporting, infrastructure or production deployment. All demo metrics fictional.

**Validation:** See `docs/delivery/INITIAL-VALIDATION.md`. Structure/XML/JSON/YAML checks and Java 21 domain smoke pass. Full Maven, Next, Docker integration tests not run due missing Maven/Docker and unavailable registry DNS.

**Next tiny steps:** (1) verify `mvn test` and Next `npm install && npm run lint && npm run build`, Docker PostgreSQL migration and API status; (2) commit/bootstrap; (3) start `001A` persistence-backed account model, password-hash tests and migration without issuing JWT yet.

**Agent update rule:** After each completed small feature, append its feature ID, commit SHA if known, actual test commands/results, decisions, scope changes, next intended slice and unresolved blockers. Never overwrite verified history or claim a commit/push without evidence.

## Checkpoint 000B/000C — 2026-11-11

Imported the original 141-file starter to the private GitHub repository as
`9fde58f`. Added Spring Boot 4's Flyway starter so the existing V1 migration
runs at startup, locked npm dependencies and switched CI and Docker to
`npm ci`. Corrected the staff preview's JSX lint error.

Java 21 `mvn verify` passed all 4 starter tests and packaged the API. A
disposable PostgreSQL 18 instance applied Flyway V1 and returned HTTP 200 from
the readiness and system status endpoints. Frontend lint, TypeScript and
production build passed with Next.js 16.4.6. PostgreSQL 17 in Docker and the
production deployment remain separate verification targets. Next: account
authentication, scoped academy catalog and disabled-by-default Zoom ingress.

## Checkpoint 001D — Browser session and portal access

Added Next.js sign-in, sign-out and session continuation for staff and students.
The server verifies current roles with the API. Access tokens are sealed in
authenticated encrypted HttpOnly cookies, and login, logout and refresh check
the request origin before changing the session. The portal no longer displays
fictional attendance as dashboard content.

Four frontend unit tests, lint, type checking, a production webpack build and
an HTTP smoke test against a mock API passed. The production GitHub Actions
build runs the Next.js default bundler and repeats the smoke test.

## Checkpoint 001A — 2026-11-11 — Account persistence and password hashing

- **Feature ID / name:** 001A — auth schema and hashed-password domain.
- **Branch:** `feature/001a-accounts`. **PR / merge SHA:** see the merged pull
  request titled "feat(auth): persist academy accounts" and `git log main`.
  A first attempt (PR #3, branch `feature/001a-account-persistence`) was closed
  unmerged: GitGuardian flagged two invented test inputs in
  `PasswordPolicyTest` as generic passwords. They were reworded, not suppressed.
- **Implemented behavior:** `AccountService` registers a pending account
  (normalized email, at least one role), sets a password (activates the account,
  bumps `auth_version`, clears lockout counters), disables an account and looks
  one up by email without exposing the hash. Passwords are hashed with Argon2id
  (16-byte salt, 19 MiB, 2 iterations, parallelism 1). Password policy: 12–128
  characters, not one repeated character, must not contain the email name.
- **Database changes:** Flyway `V2__identity_accounts.sql` adds `accounts` and
  `account_roles` with checks for normalized email, allowed status and role
  values, and "ACTIVE requires a password hash".
- **Decision:** email is unique across all academies, not per academy, because
  the sign-in form sends only email and password. Revisit only if a second
  academy must share addresses.
- **API changes:** none. No HTTP route was added; the default-deny filter chain
  is unchanged and a test asserts account/auth paths return 401/403.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker running. **Result:** 42 tests, 0 failures, 0 errors, 0 skipped
  (4 attendance, 13 email, 7 password policy, 5 hasher, 13 PostgreSQL 17
  Testcontainers integration tests including an 8-thread duplicate-email race
  and direct constraint violations). Frontend unchanged; its lint, typecheck
  and 4 unit tests passed on this machine before the change.
- **Security considerations:** hashes never leave the identity module; exception
  messages contain no password or email; role grants are academy-level only and
  do not yet express course/batch scope.
- **Known issues / not done:** no login, JWT, refresh sessions, invitations,
  recovery, lockout enforcement, MFA or audit trail yet. `failed_login_count`
  and `locked_until` exist but nothing increments them. No breached-password
  check. Local default JDK on the development Mac is 25; builds must select
  JDK 21 explicitly.
- **Next planned feature:** 001B — invitation tokens (hashed, expiring,
  single-use) and activation that calls `setPassword`.
