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

## Checkpoint 001B — 2026-11-11 — Invitations and activation (service layer)

- **001A evidence:** merged as PR #4, merge commit `de55f02`.
- **Feature ID / name:** 001B — secure invitation and account activation.
- **Branch:** `feature/001b-invitations`. **PR / merge SHA:** see the merged pull
  request titled "feat(auth): add single-use account invitations" and `git log main`.
- **Implemented behavior:** `InvitationService.issue` creates a 256-bit random
  base64url token for an account pending activation, valid 72 hours by default
  (maximum 14 days), and revokes any earlier unused invitation. `accept` claims
  the token atomically and activates the account with the chosen password.
  Unknown, expired, used and revoked tokens and non-pending accounts all fail
  with one identical error. A rejected password rolls the claim back so the
  invitation can be retried. `revoke` cancels an unused invitation.
- **Database changes:** Flyway `V3__identity_invitations.sql` adds
  `account_invitations` (SHA-256 token digest only, unique digest, expiry after
  creation, consumed and revoked mutually exclusive, partial unique index
  allowing one open invitation per account).
- **Decision:** the digest is plain SHA-256, not HMAC with a server secret. The
  token carries 256 bits of entropy, so a stolen digest cannot be brute-forced;
  this avoids introducing secret management before the key-handling slice.
- **API changes:** none; still no HTTP route. **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker running. **Result:** 56 tests, 0 failures, 0 errors, 0 skipped.
  The 14 new PostgreSQL 17 integration tests cover single use, expiry,
  revocation, re-issue, disabled accounts, retry after a weak password, 8-thread
  concurrent accept (exactly one activation) and concurrent issue (exactly one
  usable token), and direct constraint violations.
- **Security considerations:** the raw token exists only in the returned
  `IssuedInvitation`, whose `toString` redacts it. Nothing logs it.
- **Known issues / not done:** nothing delivers the token (no email or
  notification module); no HTTP activation route and therefore no rate limiting
  on activation attempts; no audit trail; no way yet to create the first
  academy owner; staff cannot issue invitations until login and authorization
  exist.
- **Next planned feature:** 001C — RS256 login, lockout using the existing
  counters, and the public activation route.

## Checkpoint 001C — 2026-11-11 — Password sign-in and RS256 access tokens

- **001B evidence:** merged as PR #5, merge commit `6d7aeff`.
- **Feature ID / name:** 001C — JWT login, lockout and activation route.
- **Branch:** `feature/001c-jwt-login`. **PR / merge SHA:** see the merged pull
  request titled "feat(auth): add password sign-in with RS256 access tokens".
- **Implemented behavior:** `POST /api/v1/auth/login` verifies the password and
  returns `{accessToken, tokenType, expiresIn}`. Tokens are RS256, 10 minutes,
  with `iss aud sub jti iat nbf exp academy_id auth_version` and a thumbprint
  `kid`. Every authenticated request re-reads the account: it must be ACTIVE,
  in the token's academy and at the token's `auth_version`; authorities come
  from stored roles. Lockout: 5 failures lock for 15 minutes; each attempt is
  counted atomically before the password check, so parallel guesses cannot
  exceed the limit. Unknown email, wrong password, pending, disabled and locked
  accounts return one identical 401. `POST /api/v1/auth/activate` redeems an
  invitation. `GET /api/v1/auth/me` returns account, academy and current roles.
  `GET /.well-known/jwks.json` publishes public keys only.
- **Key handling:** signing key from `LUMA_AUTH_SIGNING_KEY_PEM` (PKCS#8 RSA,
  2048 bits minimum); retired public keys in `LUMA_AUTH_VERIFICATION_KEYS_PEM`
  keep their tokens valid during rotation. The application refuses to start
  without a key unless `LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true`.
- **Decisions:** (1) Owner/admin sign-in returns 403 until MFA exists, unless
  `LUMA_AUTH_ALLOW_ELEVATED_LOGIN_WITHOUT_MFA=true`; MFA is split out as new
  slice 001F. (2) Spring CSRF is disabled on the API because it never
  authenticates from cookies. (3) A locked account is indistinguishable from a
  wrong password, so the BFF's 429 path is not used yet.
- **Database changes:** none (uses the V2 lockout columns).
- **API changes:** the four routes above; `contracts/openapi/platform-v1.yaml`
  updated. All other routes remain denied.
- **Frontend changes:** none. **The Next.js sign-in does not work against the
  real backend yet** because it requires `refreshToken` and `refreshExpiresIn`
  in the login response; those arrive with 001D.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker running. **Result:** 84 tests, 0 failures, 0 errors, 0 skipped.
  New: 19 real-HTTP auth tests against PostgreSQL 17 (claims, identical
  failures, lockout, 24 parallel guesses, token rejection for 15 claim
  alterations, foreign key, unknown/missing `kid`, RS512, HS256 algorithm
  confusion, unsigned token, tampered payload, disabled account, password
  change, role change, activation, JWKS, deny-by-default), 8 key tests
  (rotation, weak and malformed keys, startup without a key), 1 override test.
  A first run failed one test: tokens without `iat` were accepted because
  Spring fills that claim in; required claims are now enforced on the raw token.
- **Security considerations:** no password, token or key appears in the test
  run's log output (checked). Exceptions and `toString` redact tokens and keys.
- **Known issues / not done:** no refresh tokens, logout or session list; no
  MFA; no password recovery; no rate limiting by client address, so sign-in
  costs one Argon2 hash per request for any caller; the failure counter does
  not decay with time, only on success or lock expiry; an attacker who knows an
  email can keep that account locked; no audit trail; old hashes are not
  re-hashed on login; no first-account bootstrap; nothing delivers invitation
  tokens.
- **Next planned feature:** 001D — refresh-token rotation with reuse detection
  and logout, completing the contract the Next.js BFF already expects.

## Checkpoint 001D — 2026-11-11 — Refresh sessions and logout

- **001C evidence:** merged as PR #6, merge commit `5e904b0`.
- **Feature ID / name:** 001D — refresh rotation, reuse revocation, logout.
- **Branch:** `feature/001d-refresh-sessions`. **PR / merge SHA:** see the merged
  pull request titled "feat(auth): add rotating refresh sessions and logout".
- **Implemented behavior:** login now also returns `refreshToken` and
  `refreshExpiresIn`. `POST /api/v1/auth/refresh` exchanges the token for a new
  pair. Presenting an exchanged token again revokes the whole session,
  including the newest token. `POST /api/v1/auth/logout` ends one session and
  always returns 204; `POST /api/v1/auth/logout-all` (bearer) ends all of the
  caller's sessions. Access tokens carry `sid` and stop working the moment
  their session is revoked or expires. Idle limit 7 days, absolute limit 30
  days (`LUMA_AUTH_REFRESH_TOKEN_TTL`, `LUMA_AUTH_SESSION_MAX_LIFETIME`).
  Password change or disablement ends sessions at next use.
- **Database changes:** Flyway `V4__identity_refresh_sessions.sql` adds
  `refresh_sessions` and `refresh_tokens` (digest only; one current token per
  session enforced by a partial unique index; revocation requires a reason).
- **Decisions:** (1) every operation locks the session row, so two concurrent
  refreshes of one token give exactly one winner and then revoke the session —
  strict, with no grace window. (2) SHA-256 digest, not HMAC, as for
  invitations. (3) `RefreshSessions` uses `JdbcTemplate` rather than JPA
  entities to keep the locking SQL explicit.
- **API changes:** three routes added, login response extended; OpenAPI updated.
- **Frontend changes:** none needed. The existing Next.js sign-in, session
  continuation and sign-out now work against the real backend.
- **Tests executed:** (1) `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml
  verify` with Docker — 96 tests, 0 failures, 0 errors, 0 skipped; 12 new
  real-HTTP tests (rotation, reuse revocation, 8 concurrent refreshes, identical
  failures, idle and absolute expiry, logout, logout-all, account change,
  constraints). (2) New `scripts/auth-stack-smoke.sh`: PostgreSQL 17 + backend
  jar + Next.js dev server, 26 checks passed, run three times. Frontend lint,
  typecheck and unit tests were not re-run because no frontend file changed.
- **Test-tooling note:** the first smoke runs reported a false failure caused
  by the script itself (bash 3.2 brace-expanding a JSON literal inside
  `"$(...)"`); the script now builds JSON with `printf`. No product defect.
- **Known issues / not done:** no session list; expired/revoked rows are never
  cleaned up; a legitimate client that sends the same refresh twice (double
  submit, two tabs) is signed out; the BFF shows a generic "unavailable" error
  for the 403 an owner/admin gets before MFA exists; the smoke script is not in
  CI and uses `next dev`, not a production build; no Playwright browser tests.
  Everything listed under 001C still applies (MFA, recovery, rate limiting by
  client address, audit, first-account bootstrap, invitation delivery).
- **Next planned feature:** 002A course/batch domain with the 001E scoped
  authorization foundation; 001F admin MFA before any staff admin route.

## Checkpoint 002A — 2026-11-11 — Courses and batches (API)

- **001D evidence:** merged as PR #7, merge commit `90ed6f9`.
- **Feature ID / name:** 002A — course/batch domain, with the cross-academy and
  role-denial part of 001E.
- **Branch:** `feature/002a-courses-batches`. **PR / merge SHA:** see the merged
  pull request titled "feat(courses): add academy-scoped course and batch management".
- **Implemented behavior:** owners and administrators can list, read, create and
  update courses (`/api/v1/courses`) and batches (`/api/v1/batches`) of their
  own academy. Codes are trimmed and upper-cased; a course code is unique per
  academy and a batch code per course. Nothing is deleted: records are
  deactivated. A batch cannot move to another course or be added to an inactive
  one. Updates carry the version last read and get 409 when stale.
- **Authorization:** two layers. The route rule admits only owner/admin; the
  services repeat that check through `CurrentAccount` and bind every query to
  the caller's academy, taken from the verified token and never from the
  request. A record of another academy returns the same 404 as a missing one.
  Instructor, Attendance Officer, Analyst and Student get 403 on all of these
  routes; scoped access for them needs 002B.
- **Database changes:** Flyway `V5__courses_batches.sql` adds `batches` and, on
  `courses`, `updated_at`, `version` and code/title checks. A composite foreign
  key keeps a batch in its course's academy.
- **API changes:** eight operations; OpenAPI updated with schemas.
- **Frontend changes:** none. There is no staff screen for courses or batches.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 123 tests, 0 failures, 0 errors, 0 skipped. 27 new: 14 course
  and 11 batch tests over real HTTP on PostgreSQL 17 (validation, uniqueness,
  8-way create and update races, cross-academy 404s, role 403s, role removal
  mid-session, no delete, constraints) and 2 tests calling the services
  directly to prove role and academy checks without the route rules.
  `scripts/auth-stack-smoke.sh` re-run: 26 checks passed.
- **Test support:** `StaffApiIntegrationTest` (enables the development-only
  elevated-login override so admin tokens can be obtained) and
  `resetDatabase()` (`TRUNCATE academies CASCADE`).
- **Known issues / not done:** owner/admin still cannot sign in without the
  development override, so these routes are unusable in a real deployment until
  001F (MFA). No audit record of who changed what. Lists are unpaginated
  (bounded by academy size). No staff UI. No timetable, enrollments, staff
  assignments or class sessions. Academy policy on whether a student may be in
  several batches of one course is still an open question for 002B.
- **Next planned feature:** 001F admin MFA, then 002B.

## Checkpoint 002A-fix — 2026-11-11 — Timestamp precision, and a broken `main`

- **What happened:** PR #8 (002A) was merged as `dc808cc` at 07:52:27 UTC by the
  repository owner's account while its backend CI job was still running. That
  job then failed (3 of 123 tests), so `main` was red until this fix.
- **Defect:** on Linux the JVM clock has nanosecond precision, while PostgreSQL
  stores microseconds. A course or batch returned by create/update therefore
  carried timestamps that differed in the last three digits from the same
  record read back. macOS clocks report microseconds, so every local run passed.
- **Fix:** `shared.time.DbTime` truncates to microseconds; used for entity
  timestamps (`Course`, `Batch`, `Account`) and invitation expiry.
- **Tests:** new `DbTimeTest` and a service test asserting that what a write
  returns equals what a read returns. The authoritative check is the Linux CI
  job, because the defect cannot occur on the development Mac.
- **Process change for agents:** PRs have been merged within about a minute of
  opening, before checks finish. Open PRs as **drafts**, wait for all checks,
  then mark ready and merge. Local green on macOS is not sufficient evidence.

## Checkpoint 001F — 2026-11-11 — MFA for owner and administrator accounts (backend)

- **002A evidence:** merged as PR #8 (`dc808cc`), fixed by PR #9 (`ad7e013`).
- **Feature ID / name:** 001F — admin MFA.
- **Branch:** `feature/001f-admin-mfa`. **Backend commit:** `f7bf93a`.
  See the [001F pull request](https://github.com/Suthankan1/luma-pca/pulls?q=is%3Apr+head%3Afeature%2F001f-admin-mfa)
  for Linux CI and merge status.
- **Implemented behavior:** the password step returns an MFA challenge instead
  of tokens for owner/admin accounts and for any account with an authenticator.
  `POST /auth/mfa/enroll` issues a TOTP secret on first use; `POST
  /auth/mfa/verify` accepts a 6-digit code or a recovery code and starts the
  session; `POST /auth/mfa/reset` (bearer plus password) removes the
  authenticator and ends all sessions. Ten recovery codes are returned once, at
  enrolment. Codes cannot be replayed. Five wrong codes lock the account for 15
  minutes by default. Access and refresh reject pre-MFA sessions for accounts
  promoted to owner/admin. Verification and session creation commit together.
  Reset clears MFA and revokes sessions in one transaction.
- **Removed:** `LUMA_AUTH_ALLOW_ELEVATED_LOGIN_WITHOUT_MFA` and the 403 it
  guarded. Tests now sign administrators in through real MFA.
- **Configuration:** new required `LUMA_AUTH_MFA_ENCRYPTION_KEY` (Base64, 32
  bytes). The backend refuses to start without it unless the ephemeral
  development flag is set.
- **Rollout:** provision a stable MFA encryption key before starting the
  backend in a persistent environment. Existing sessions default to
  `mfa_verified = false`. Preserve the key and the applied migration when
  planning a rollback, and account for the earlier elevated-login policy.
- **Database changes:** Flyway `V6__identity_mfa.sql` adds `account_totp`
  (AES-256-GCM ciphertext), `account_recovery_codes` and `mfa_challenges`
  (digests), and `refresh_sessions.mfa_verified`.
- **API changes:** three routes; login may now return an MFA challenge. OpenAPI updated.
- **Frontend changes:** none. **The Next.js sign-in cannot complete MFA**: it
  shows a generic "unavailable" error for administrators (they could not sign
  in before this slice either). New slice 001G covers the browser step.
- **Tests executed on macOS:** Java 21 `mvn -B -ntp -f backend/pom.xml clean
  verify` with Docker and PostgreSQL 17 passed 154 tests, with zero failures,
  errors or skips. Coverage includes RFC 6238 vectors, encrypted storage,
  parallel attempts, replay, recovery codes, session rejection after promotion,
  reset and retry after a session write fails. Frontend lint, typecheck, four
  unit tests, production build and session HTTP smoke passed. The real-stack
  `scripts/auth-stack-smoke.sh` passed 26 checks of student sessions. The
  repository structure check passed and the OpenAPI YAML parsed.
- **Decisions:** (1) required only for owner/admin; other roles cannot enrol
  yet. (2) Trust on first use: the first password sign-in to a new owner/admin
  account enrols the authenticator. (3) SHA-1 TOTP for authenticator-app
  compatibility. (4) No key identifier is stored with ciphertexts, so rotating
  the MFA encryption key means re-enrolling everyone.
- **Known issues / not done:** no browser MFA step; no way for an owner to
  reset another person's MFA (a locked-out administrator without recovery codes
  needs a database change); recovery codes cannot be regenerated without a full
  reset; signing in twice within one 30-second step fails the second time by
  design; no audit trail; expired challenges are only cleaned up at the next
  sign-in of the same account. All earlier open items still apply (recovery,
  rate limiting by client address, first-account bootstrap, invitation delivery).
- **Next planned feature:** 001G browser MFA step, then 002B.

## Checkpoint 001G — 2026-11-11 — Browser MFA integration (Next.js BFF)

- **001F evidence:** merged as PR #10, merge commit `8ee04c1`.
- **Feature ID / name:** 001G — Next.js browser MFA integration for academy owners and administrators.
- **Branch:** `feature/001g-browser-mfa`.
- **Implemented behavior:**
  - Password sign-in detects `mfaRequired: true` from the backend and seals the short-lived challenge in an encrypted, HttpOnly, SameSite=Strict cookie (`__Host-luma-mfa` or `luma-mfa`) without creating an authenticated session.
  - Initial enrollment screen (`/sign-in/mfa`): requests the setup secret once from `POST /api/v1/auth/mfa/enroll` and retains it in the sealed challenge cookie so page refreshes do not invalidate an unconfirmed secret. Renders a locally generated vector QR code (no external network requests or tracking) and accessible manual secret entry.
  - Existing MFA verification: directly prompts for the 6-digit TOTP code or a single-use recovery code. Submissions go to `/api/session/mfa-verify`.
  - One-time recovery codes presentation (`/sign-in/mfa/recovery-codes`): issued upon initial enrollment, sealed in a short-lived `__Host-luma-recovery` cookie with `Cache-Control: no-store`. Includes clipboard copy action with accessible feedback and requires explicit user acknowledgment. Once acknowledged, the recovery cookie is cleared and the user enters the staff portal (`/staff`). Codes cannot be re-viewed on refresh or back-navigation.
  - Cancellation (`/api/session/mfa-cancel`) clears all MFA cookies and returns to sign-in. Expired challenges cleanly redirect to sign-in with an expiration message.
  - Protected portals (`/staff`, `/student`) reject requests with pending MFA challenges, preventing privilege escalation.
  - Origin and Sec-Fetch-Site checks enforced on all state-changing session routes.
- **Dependencies added:**
  - `qrcode` and `@types/qrcode`: for local vector QR code generation.
  - `@playwright/test`: for automated end-to-end browser verification.
- **CI updates:**
  - `.github/workflows/frontend-ci.yml`: added `npx --prefix frontend playwright install --with-deps chromium` and `npm --prefix frontend run test:e2e` to verify full browser authentication flows in CI.
- **Tests executed on macOS:**
  1. `npm --prefix frontend test`: 11 unit tests passing (token parsing, MFA challenge detection, malformed challenge rejection, enrollment/verify outcome validation, sealed cookie encryption, tampering resistance, cross-purpose rejection, recovery code isolation).
  2. `npm --prefix frontend run lint`: ESLint passed with 0 errors and 0 warnings.
  3. `npm --prefix frontend run typecheck`: TypeScript compiler checks passed without errors.
  4. `npm --prefix frontend run build`: Next.js production build succeeded with Turbopack (all static and dynamic routes compiled).
  5. `npm --prefix frontend run test:smoke`: 43 HTTP integration assertions passed against mock backend and Next.js production server.
  6. `npm --prefix frontend run test:e2e`: 10 Playwright browser tests passed (5 test scenarios across Desktop Chromium and Mobile Pixel 7 viewports: enrollment flow, existing TOTP login, recovery code verification, error handling, cancellation, and student/instructor regression).
  7. `mvn -B -ntp -f backend/pom.xml verify`: 154 backend tests passed with Java 21 on PostgreSQL 17 Testcontainers.
  8. `scripts/auth-stack-smoke.sh`: all 43 real-stack checks passed with PostgreSQL 17 in Docker, Spring Boot backend jar, and Next.js dev server.
  9. `python3 scripts/verify_structure.py`: 25 required repository structure files present.
- **Decisions:**
  (1) The unconfirmed enrollment secret is stored in the AES-256-GCM encrypted challenge cookie so users can refresh or take time scanning without generating a new unconfirmed secret and invalidating the QR code.
  (2) One-time recovery codes are delivered via a short-lived sealed cookie rather than URL parameters or localStorage, and wiped immediately upon acknowledgment.
  (3) In `auth-server.ts`, loopback origins (`localhost`, `127.0.0.1`, `[::1]`) are permitted over HTTP even when running `next start` for local development and test runners, while production domains strictly require HTTPS.
- **Known issues / not done:**
  No self-service MFA reset for locked-out administrators without recovery codes (requires owner assistance or database intervention); recovery codes cannot be regenerated without resetting MFA; no client IP rate limiting yet.
- **Next planned feature:** 002B — Students, enrollments and staff assignments.

## Checkpoint 002B-1 — 2026-11-11 — Students and batch enrollments

- **001G evidence:** merged as PR #11, merge commit `4ecbcb9`; CI green on `main`.
- **Feature ID / name:** 002B part 1 — student records, enrollments, student
  own-record access. Part 2 (staff assignments) follows separately.
- **Branch:** `feature/002b-students-enrollments`. **PR / merge SHA:** see the
  merged pull request titled "feat(students): add student records and batch enrollments".
- **Implemented behavior:** owners and administrators create students; each
  creation also creates a STUDENT sign-in account pending activation, in one
  transaction. Student numbers are upper-cased, unique per academy and
  immutable. Students list with keyset pagination (`after`, `limit` ≤ 200).
  Enrollments link a student to a batch (unique per batch and student; one
  student may be in several batches, including two of one course). Withdrawal
  sets a date and keeps the row; clearing it re-enrols. Inactive batches and
  students cannot take new enrollments. A student reads only
  `/api/v1/me/student` and `/api/v1/me/enrollments`.
- **Authorization:** route rules plus service checks; "own" is resolved from
  the token's account and academy together, never from input. Students get 403
  on all staff routes, including for their own record, and never see rosters or
  classmates. Instructor, Attendance Officer and Analyst still get 403 everywhere
  here; scoped access comes with part 2. Another academy's records return 404.
- **Database changes:** Flyway `V7__students_enrollments.sql`: `students`,
  `enrollments`, and `uq_accounts_id_academy` so composite foreign keys keep a
  student's account, and an enrollment's batch and student, in one academy.
- **API changes:** ten operations; OpenAPI updated with `Student` and `Enrollment` schemas.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 174 tests, 0 failures, 0 errors, 0 skipped on macOS. New: 10
  student and 9 enrollment tests over real HTTP on PostgreSQL 17 (no partial
  rows on duplicates, 8-way create and enrol races, pagination, immutability,
  cross-academy 404s, role 403s, student self-only access, constraints) and 1
  service-level test of role, academy and ownership checks. Linux CI result is
  on the pull request.
- **Decisions:** (1) creating a student always creates their account; an email
  already used by any account is refused. (2) Withdrawal is a date, not a
  delete. (3) Deactivating a student does not disable their sign-in, so they keep
  access to their own history. (4) No rule yet against two simultaneous batches
  of one course; that is academy open question P1 and is deliberately allowed.
- **Known issues / not done:** no way to send the invitation to a new student
  (invitation issuance has no route yet); no bulk import; no name search; no
  audit trail; the student record shows email from the account and cannot
  change it; no staff UI.
- **Next planned feature:** 002B part 2 — staff assignments with batch scope.
