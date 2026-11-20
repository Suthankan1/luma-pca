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

## Checkpoint 002B-2 — 2026-11-11 — Staff assignments and batch-scoped access

- **002B part 1 evidence:** merged as PR #12, merge commit `818c6f4`; backend CI green on `main`.
- **Feature ID / name:** 002B part 2 — staff assignments; completes 002B and the
  batch-scope part of 001E for the routes that exist.
- **Branch:** `feature/002b-staff-assignments`. **PR / merge SHA:** see the merged
  pull request titled "feat(batches): scope instructors and attendance officers to assigned batches".
- **Implemented behavior:** owners and administrators assign instructors and
  attendance officers to batches by email (`POST /api/v1/batches/{id}/assignments`),
  list them, and remove them (`DELETE /api/v1/assignments/{id}`). The account must
  be in the academy, not disabled, and already hold the role. Assigned staff read
  only their batches, those batches' rosters, and `GET /api/v1/me/assignments`.
- **Authorization:** access needs the assignment and the role held now; removing
  either takes effect on the next request without signing in again. An
  unassigned batch returns the same 404 as a missing one (a role with no batch
  access at all, such as Analyst or Student, gets 403). Assigned staff cannot
  change batches, enrol students, see assignments, or reach courses or students.
  An email from another academy is reported like an unknown one.
- **Behavior change:** instructors and attendance officers previously got 403 on
  `GET /api/v1/batches`; without assignments they now get an empty list, and 404
  for individual batches and rosters. Four existing tests were updated for this.
- **Database changes:** Flyway `V8__staff_assignments.sql` (composite academy
  foreign keys; unique per batch, account and role; role limited to INSTRUCTOR
  and ATTENDANCE_OFFICER; deleted with the account).
- **API changes:** four operations; OpenAPI updated with an `Assignment` schema
  and the widened read access on batches and rosters.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 186 tests, 0 failures, 0 errors, 0 skipped on macOS. New: 11
  real-HTTP tests on PostgreSQL 17 (Batch A versus Batch B for instructors and
  officers, read-only, role removal mid-session, unassignment, suitable accounts,
  8-way duplicate race, cross-academy, students and analysts refused,
  constraints) and 1 service-level scope test. Linux CI result is on the pull request.
- **Decisions:** (1) 404, not 403, for an unassigned batch. (2) Assign by email
  so administrators need no account ids. (3) Assignments are deleted, not
  soft-deleted: they are access grants, not evidence; an audit trail is still
  missing. (4) Assigned staff see student names and numbers on rosters, not emails.
- **Known issues / not done:** no API to create staff accounts or change roles
  (accounts must still be seeded); no route to send invitations; no audit trail;
  no staff UI. Analyst aggregate access is not designed yet.
- **Next planned feature:** 002C class sessions shared between batches.

## Checkpoint 002C — 2026-11-11 — Class sessions shared between batches

- **002B part 2 evidence:** merged as PR #13, merge commit `43c51ec`; backend CI green on `main`.
- **Feature ID / name:** 002C — sessions and shared-batch schedule.
- **Branch:** `feature/002c-class-sessions`. **PR / merge SHA:** see the merged pull
  request titled "feat(sessions): schedule classes shared between batches".
- **Implemented behavior:** owners and administrators schedule a class for 1–20
  batches (`POST /api/v1/class-sessions`) with UTC times, an IANA time zone
  (default Asia/Colombo) and at most 12 hours' length, and replace any field,
  including the batch set and SCHEDULED/CANCELLED status (`PUT`). A class shared
  by several batches is one session record. `GET /api/v1/class-sessions` lists by
  start time in a window of at most 92 days, optionally for one batch.
  `GET /{id}/students` lists the students expected at the class, each once with
  all of the session's batches they belong to.
- **Eligibility rule (decision to review):** a student is expected if enrolled in
  one of the batches on the class's local calendar date in its time zone. A
  withdrawal date is the first day the student is no longer enrolled.
- **Authorization:** assigned instructors and attendance officers read sessions
  that include one of their batches, see only those batches in the session and
  only those batches' students, so a shared class never reveals the other batch.
  Sessions without their batches return 404. Students and analysts have no
  session access yet.
- **Database changes:** Flyway `V9__class_sessions.sql`: `class_sessions` and
  `session_batches` (composite same-academy keys; primary key on session and
  batch; batches used by sessions cannot be deleted). The batch set is part of
  the session entity, so a batch-only edit advances the version.
- **API changes:** five operations replacing the planned `/api/v1/class-sessions`
  entry; OpenAPI updated. Attendance remains planned.
- **Docs fix:** README status text now lists students, enrollments, staff
  assignments and sessions; two earlier README edits (002B parts 1 and 2) had
  silently not applied after the 001G README rewrite.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 198 tests, 0 failures, 0 errors, 0 skipped on macOS. 12 new
  real-HTTP tests on PostgreSQL 17: one record per shared class, a student in
  two batches expected once, local-date eligibility (Colombo versus UTC),
  validation, foreign and inactive batches, window listing, batch-only edits
  advancing the version, an 8-way edit race leaving exactly one batch set,
  instructor and officer views of a shared class, cross-academy, students and
  analysts refused, constraints. Linux CI result is on the pull request.
- **Known issues / not done:** no attendance records yet (004A will key them on
  session and student); no Zoom webinar mapping (003D); no student timetable
  route; no recurring schedules (each class is created individually); classes
  may be shared across courses, pending academy question P1; no staff UI.
- **Next planned feature:** staff account administration, then Zoom ingress (003B).

## Checkpoint 001H — 2026-11-11 — Staff account administration, invitations and audit trail

- **002C evidence:** merged as PR #14, merge commit `8c8988d`; backend CI green on `main`.
- **Feature ID / name:** 001H — staff account administration (new roadmap row).
- **Branch:** `feature/staff-account-admin`. **PR / merge SHA:** see the merged pull
  request titled "feat(identity): administer staff accounts and issue invitations".
- **Implemented behavior:** owners and administrators list staff accounts
  (students never appear), create staff accounts pending activation, replace
  roles (with version), disable accounts (ends every session; tokens rejected),
  and issue activation invitations for pending staff and student accounts. The
  invitation token is returned once with `Cache-Control: no-store`, never stored
  or logged; re-issuing revokes the previous one.
- **Escalation and lock-out rules:** only an owner grants, changes or removes
  OWNER/ADMIN, or disables or invites such an account; nobody changes their own
  roles or disables themselves; an academy always keeps an active owner. Each
  change locks the academy row and re-reads the caller's roles under the lock, so
  two owners removing or disabling each other at once leave exactly one (tested).
- **Audit trail:** new `audit` module; Flyway `V10__audit_events.sql` with an
  append-only trigger. Staff creation, role changes, disablement and invitations
  record actor, action and target id in the same transaction; details hold role
  names and expiry only, never email or tokens. Refused actions record nothing.
  There is no API to read the audit trail yet.
- **API changes:** six operations; OpenAPI updated with `StaffAccount` and `StaffRole`.
- **Frontend changes:** none. **No browser page redeems an invitation yet**;
  `POST /api/v1/auth/activate` must be called directly. Slice 001I adds the page.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 212 tests, 0 failures, 0 errors, 0 skipped on macOS. 14 new
  real-HTTP tests on PostgreSQL 17 (who may grant what, self-change refused,
  invitation end to end for staff and students, re-issue, versions, immediate
  role and disable effects, two concurrent owner-versus-owner races run three
  times, cross-academy, non-managers, audit contents and append-only enforcement).
  Two test mistakes were fixed before committing (a stale version in a race test,
  a convoluted assertion); no product defect. Linux CI result is on the pull request.
- **Known issues / not done:** no email delivery; no browser activation page; no
  re-enable for disabled accounts; no owner-driven MFA reset for another person;
  no audit read API; first-account (academy owner) provisioning still needs the
  database; removing a staff role leaves its batch assignments in place (they
  grant nothing without the role).
- **Next planned feature:** 001I browser activation page, then 003B Zoom webhook receipt.

## Checkpoint 001I — 2026-11-11 — Browser account activation

- **001H evidence:** merged as PR #15, merge commit `cbbae6e`; backend CI green on `main`.
- **Feature ID / name:** 001I — browser account activation.
- **Branch:** `feature/001i-activation-page`. **PR / merge SHA:** see the merged
  pull request titled "feat(web): activate invited accounts in the browser".
- **Implemented behavior:** new Next.js page `/activate`. An administrator sends
  `https://<web origin>/activate#token=<code>`; the page copies the code from the
  fragment into the form and removes it from the address bar. The person may
  also paste the code or the whole link. The form posts to the BFF action
  `/api/session/activate` (same origin check as sign-in), which rejects a missing
  code or mismatched passwords locally, calls `POST /api/v1/auth/activate`, and
  redirects to `/sign-in?activated=1` on success. On a weak password, the
  person returns to the form with the code kept in the fragment; an invalid,
  used or expired code returns to an empty form. The sign-in page shows
  "Your account is active". Activation never signs anyone in.
- **Backend change:** the invitation response gains `activationPath`
  (`/activate#token=...`); OpenAPI updated.
- **Defect found by the browser tests and fixed before commit:** the first
  version set the page's referrer policy to `no-referrer`. That makes browsers
  send `Origin: null` on form posts, so the BFF's cross-site check refused every
  activation with 403. Curl-based checks could not have caught it. The policy
  was unnecessary (fragments are never sent in a Referer) and was removed.
- **Tests executed (macOS):** frontend lint, type-check, 15 unit tests (4 new:
  code extraction from codes and links, local form checks, fragment-only
  redirects), production build, HTTP smoke test; Playwright 16 tests, 6 new
  (3 scenarios × desktop and mobile: invalid, pasted-link, mismatch, weak and
  successful activation, reuse refused, malformed code caught locally,
  cross-site post refused, labels), run twice. Backend: 212 tests. Real stack
  (`scripts/auth-stack-smoke.sh`, PostgreSQL 17 + backend jar + Next.js): 55
  checks, now activating the student through the page, including a password the
  backend refuses and a reused code, and confirming the activation code never
  appears in the frontend log. Test-only fixes: label lookups made exact, the
  Next.js route announcer excluded from alert lookups, and one mock activation
  code per browser project.
- **Known issues / not done:** no email delivery (the administrator sends the
  link); no staff screen to create staff or issue invitations (API only);
  activation does not sign the person in; no rate limit on activation attempts
  beyond the 256-bit code space.
- **Next planned feature:** 003B Zoom webhook receipt against fixtures.

## Checkpoint 003B — 2026-11-11 — Signed Zoom webhook receipt (fixtures only)

- **001I evidence:** merged as PR #16, merge commit `bc2665b`; backend and frontend CI green on `main`.
- **Feature ID / name:** 003B — webhook verification and durable receipt.
- **Branch:** `feature/003b-zoom-webhook-ingress`. **PR / merge SHA:** see the
  merged pull request titled "feat(zoom): verify and durably record Zoom
  webhooks". Two attempts (PR #17 and PR #18) were closed unmerged because
  GitGuardian flagged a random-looking sample `plainToken` used in two test files
  as a generic high-entropy secret (incident 39943027, which may remain open for
  the owner to resolve as test data). The first fix wrongly targeted a
  hard-coded HMAC (now computed in the test, which is still better); the fixture
  is now the low-entropy `zoomUrlValidationFixture`. Lesson for agents: read the
  flagged string itself, and keep random-looking literals out of tests.
- **Source checked:** Zoom's webhook documentation was read on 2026-11-11
  (https://developers.zoom.us/docs/api/webhooks/). It documents the signature
  scheme, URL validation (3-second limit), and retries of 5xx only (5, 20 and 60
  minutes later). It documents **no** replay window and **no** event identifier.
- **Implemented behavior:** `POST /api/v1/zoom/webhooks`, off (404) unless
  `LUMA_ZOOM_WEBHOOK_SECRET_TOKEN` and `LUMA_ZOOM_WEBHOOK_ACADEMY_ID` are set.
  Verifies `x-zm-signature` over the raw body and the request timestamp (within 5
  minutes, our choice), answers `endpoint.url_validation`, stores the four
  webinar start/end/join/leave event types and drops other verified types. The
  receipt is committed before the 2xx; an identical redelivery is acknowledged
  without a second row; a storage failure answers 503 so Zoom retries.
- **Security decision beyond Zoom's sample code:** validation requests must be
  signed, and a `plainToken` containing `:` is refused, so the validation answer
  can never serve as a signature for a forged event.
- **Database changes:** Flyway `V11__zoom_webhook_receipts.sql`.
- **API changes:** the planned webhook entry is replaced by the implemented one in OpenAPI.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 229 tests, 0 failures, 0 errors, 0 skipped on macOS. 17 new: 6
  unit tests against reference values computed independently with Python's
  `hmac` module (tampering, freshness both ways, malformed headers,
  configuration), 10 real-HTTP tests on PostgreSQL 17 (storage, URL validation,
  forgery attempt, ten rejection cases with empty bodies, redelivery, 12
  concurrent duplicates producing one receipt, malformed and unused events, size
  and content-type limits, 503 then successful retry, log capture proving no
  names, emails, secret or signatures are logged), 1 disabled-by-default test.
  The Zoom tests were run three times. Linux CI result is on the pull request.
- **Known issues / not done:** **no real Zoom delivery has been received**; the
  5-minute window may reject retries if Zoom keeps the original timestamp (to
  verify in 003A); dedup on the exact body will not merge a retry whose body
  differs; nothing processes receipts yet (003C); one academy per deployment;
  payload retention policy undecided; the `x-zm-request-id` header is not used
  because Zoom does not document it.
- **Next planned feature:** 003C receipt processing worker; 003A needs the academy's Zoom credentials.

## Checkpoint 003C — 2026-11-11 — Zoom receipt processing worker

- **003B evidence:** merged as PR #19, merge commit `eea9743`; backend CI and GitGuardian green.
  The closed branches of PRs #3, #17 and #18 were deleted on GitHub at the owner's request.
- **Feature ID / name:** 003C — outbox/worker (in-database queue).
- **Branch:** `feature/003c-receipt-worker`. **PR / merge SHA:** see the merged pull
  request titled "feat(zoom): process webhook receipts with retries and dead letters".
- **Implemented behavior:** a poller in the API process claims due receipts with
  `FOR UPDATE SKIP LOCKED` (safe on several instances), runs the handler for the
  event type and marks the receipt in the same transaction. A failed attempt is
  undone with a JDBC savepoint while the claim is kept, then retried after 30 s
  doubling to 1 h, ten attempts over about 3 hours, or dead-lettered at once for
  malformed payloads and unknown types. Owners and administrators list receipts
  by status without payloads and requeue dead letters with an audited reason.
  The first handler records each webinar event per occurrence
  (`zoom_occurrence_events`), one row per receipt, ordered by Zoom's event time.
- **Source checked:** Zoom's event reference (developers.zoom.us Meetings
  webhooks page, read 2026-11-11) lists `payload.object.id` and
  `payload.object.uuid` as required strings on `webinar.participant_joined`. Only
  those are read; participant fields wait for a real test webinar.
- **Database changes:** Flyway `V12__zoom_receipt_processing.sql`.
- **API changes:** `GET /api/v1/zoom/receipts`, `POST /api/v1/zoom/receipts/{id}/replay`; OpenAPI updated.
- **Configuration:** `LUMA_ZOOM_PROCESSING_ENABLED` (default true),
  `LUMA_ZOOM_PROCESSING_POLL_INTERVAL` (default PT5S). Integration tests turn
  polling off and drive the processor directly.
- **Corrections made during the slice:** (1) Spring's JPA transaction manager
  refuses savepoints by default; instead of enabling nested transactions for the
  whole application, the processor sets a JDBC savepoint on the transaction's own
  connection, so handlers must write through `JdbcTemplate`. (2) My first retry
  schedule claimed "about 4 hours" but added up to about 1 hour; the default is
  now ten attempts (about 3 hours) and a test pins the arithmetic.
  (3) The first CI run failed on Linux only: the 003B "ingress is off" test
  counted receipts without clearing the shared test database, and on Linux it ran
  after the new worker tests. It now resets first; the full suite was then run
  locally in reverse and random class order (241 passing each time).
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 241 tests, 0 failures, 0 errors, 0 skipped on macOS. 12 new: 3
  backoff unit tests and 9 PostgreSQL 17 tests (out-of-order events, idempotent
  reprocessing, malformed payloads dead-lettered without quoting them, partial
  writes rolled back on failure, ten-attempt dead-lettering with doubling waits,
  4 workers over 200 receipts with every receipt attempted exactly once, a receipt
  locked by another worker skipped until released, listing and audited replay,
  access limited to owners and administrators). Worker tests run three times;
  no payload text in logs. Linux CI result is on the pull request.
- **Known issues / not done:** polling runs inside the API process (a separate
  worker and SQS are the production plan); no alerting on dead letters; the
  occurrence log is not yet linked to class sessions or students (003D); events
  are still unproven against a real Zoom account (003A).
- **Next planned feature:** 003D occurrence and identity mapping (participant
  parts need 003A); staff screens are the other large open area.

## Checkpoint 003D-1 — 2026-11-11 — Zoom occurrence to class session linking

- **003C evidence:** merged as PR #20, merge commit `e200a71`; backend CI and GitGuardian green.
- **Feature ID / name:** 003D part 1 — webinar occurrence mapping. Part 2 (student
  identity from registrants and participants) needs a real test webinar (003A).
- **Branch:** `feature/003d-occurrence-mapping`. **PR / merge SHA:** see the merged
  pull request titled "feat(zoom): link webinar occurrences to class sessions".
- **Implemented behavior:** class sessions take an optional `zoomWebinarId`
  (spaces and hyphens removed; digits, 9–20 long, an assumption to confirm in
  003A). The worker links each new occurrence to the single SCHEDULED class on
  that webinar whose time ±60 minutes contains the event; zero or several
  candidates leave it for review. Recurring webinars and restarts link correctly;
  cancelled and other academies' classes never match. Owners and administrators
  list occurrences, record audited decisions (link, relink, or "not a class") that
  the matcher never overrides, and re-run matching after adding webinar IDs.
- **Database changes:** Flyway `V13__zoom_occurrence_links.sql`.
- **API changes:** `zoomWebinarId` on class sessions (omitted on PUT clears it);
  `GET /api/v1/zoom/occurrences`, `POST /api/v1/zoom/occurrence-links`,
  `POST /api/v1/zoom/occurrences/auto-link`. Occurrence UUIDs can contain `/` and
  `+`, so they travel in bodies and queries, never in paths.
- **Frontend changes:** none.
- **Tests executed:** `JAVA_HOME=<JDK 21> mvn -B -ntp -f backend/pom.xml verify`
  with Docker — 251 tests, 0 failures, 0 errors, 0 skipped on macOS, also in
  random and reverse class order. 10 new PostgreSQL 17 tests: webinar ID input,
  automatic link with one decision across three events, recurring and restarted
  webinars, six no-guess cases (too late, other webinar, ambiguous, cancelled,
  other academy) plus the exact one-hour edge, manual decisions with audit
  contents and a later event not overruling them, validation, re-running the
  matcher, 4 workers over 40 events creating one link, access control,
  constraints. Two test mistakes fixed before commit (a value that was in range,
  and a cross-academy setup that could silently fall back to a random ID).
  Linux CI result is on the pull request.
- **Known issues / not done:** no staff screen for the review list; changing a
  class's webinar ID or time does not revisit existing links; the 60-minute
  slack is fixed; nothing maps participants to students yet; still unproven
  against a real Zoom account.
- **Next planned feature:** staff screens for the academic API; attendance
  (004A) waits for participant identity (003D part 2, needs 003A).

## Checkpoint UI-1 — 2026-11-11 — Staff screens: shell, courses and batches

- **003D part 1 evidence:** merged as PR #21, merge commit `e04fbd8`; backend CI and GitGuardian green.
- **Feature ID / name:** UI-1 — first staff screens (new roadmap rows UI-1 to UI-3).
- **Branch:** `feature/staff-ui-courses-batches`. **PR / merge SHA:** see the merged
  pull request titled "feat(web): staff layout and courses and batches screen".
- **Design source:** Figma file QVzsewJyikM94Xp07hDwwi, frames "DESKTOP / 01 /
  Your classroom, in focus." (12:27) and "DESKTOP / 10 / Identity mapping /
  directory" (12:908), read 2026-11-11 (read-only). There is no Figma frame for
  courses and batches; the screen uses those frames' shell and directory patterns.
  The prototype's fictional figures were not copied. Fonts were not changed.
- **Implemented behavior:** new staff shell (navy rail with numbered sections,
  breadcrumb, heading, subtitle) on `/staff` and `/staff/courses`. Owners and
  administrators see courses with their batches, create courses and batches, and
  deactivate or reactivate either, with inline success and error messages; other
  staff see an explanation instead of the forms. Data loads on the server through
  `lib/staff-api.ts`, which reads the sealed HttpOnly access cookie and sends it
  only to the API; an expired session goes to sign-in. Changes go through Server
  Actions that repeat the configured-origin check, validate input (including
  UUID shape for ids from hidden fields) and rely on the API for authorization.
- **Defect found by the browser tests and fixed before commit:** React 19 resets
  a form after its Server Action finishes, so a refused submission erased what the
  person had typed. Refusals now return the submitted text, which becomes the
  fields' defaults; successes still clear the form.
- **API changes:** none. **Database changes:** none.
- **Tests executed (macOS):** frontend lint, type-check, 22 unit tests (7 new:
  roles, UUID guard, course and batch rules, error wording, dates, submitted
  values), production build, HTTP smoke test; Playwright 22 tests (6 new: 3
  scenarios × desktop and mobile: full create/refuse/deactivate/reactivate flow
  with values kept after refusals, instructor sees explanation, signed-out
  redirect and no horizontal overflow on a phone), run twice. Backend unchanged.
- **Not verified:** the browser tests use the mock backend, which mirrors the
  real API's status codes and problem details for these cases; the screens have
  not been driven against the real backend in a browser.
- **Known issues / not done:** no editing of codes, titles or dates yet (only
  activation); no pagination needed yet (lists are academy-sized); other areas
  still API-only (UI-2, UI-3); the Figma typeface is not adopted.
- **CI change in the same PR:** the first two CI attempts failed before any test
  ran: `playwright install --with-deps` runs apt-get on every build, and the runner
  could not resolve Ubuntu's package servers (GitHub reported no incident; same
  runner image as an earlier passing run). The browser tests now run in their own
  job inside `mcr.microsoft.com/playwright:v1.65.0-noble`, which has Chromium and its
  libraries preinstalled; a step fails clearly if `@playwright/test` in the lockfile
  drifts from the image tag. Lint, type-check, unit, build and smoke run in a
  parallel job with an npm cache. Both jobs have a 15-minute limit.
- **Next planned feature:** UI-2 students and enrollments screens.

## Checkpoint UI-2 — 2026-11-11 — Staff screens: students, enrolments, invitations, rosters

- **UI-1 evidence:** merged as PR #22, merge commit `1ffe567`; frontend CI green (checks and e2e jobs).
- **Feature ID / name:** UI-2.
- **Branch:** `feature/staff-ui-students`. **PR / merge SHA:** see the merged pull
  request titled "feat(web): student, enrolment and roster screens".
- **Implemented behavior:** `/staff/students` (Active/Inactive/All filter, 50 per
  page with Next and First links, add form); `/staff/students/{id}` (rename,
  deactivate/reactivate, create an invitation link shown once with copy button
  and Colombo-time expiry, enrol in active batches, withdraw on a date, re-enrol);
  `/staff/batches/{id}` roster, readable by owners and administrators and by
  instructors and attendance officers assigned to the batch (the API answers 404
  otherwise, shown as not found). Non-managers see explanations, and on Courses
  & batches a list of their assigned batches. Malformed or unknown ids are 404.
- **Invitation links:** built from `LUMA_WEB_ORIGIN` plus the API's
  `activationPath`; returned only in the Server Action's response to the
  administrator, never stored or put in a URL; the path shape is checked first.
- **API changes:** none. **Database changes:** none.
- **Tests executed (macOS):** frontend lint, type-check, 26 unit tests (4 new),
  production build; Playwright 30 tests (8 new: 4 scenarios × desktop and
  mobile), run twice. **Real stack, new:** `scripts/staff-ui-real-stack.sh`
  (PostgreSQL 17, backend jar, `next start`) drives the whole onboarding in a real
  browser, including MFA enrolment with computed codes and the student signing
  in; passed twice; database counts and the audit row checked; no secrets in logs.
  This also verifies the UI-1 screens against the real backend.
- **Test-only fix before commit:** the students spec first reused the courses
  spec's course code in the shared mock backend; codes are now distinct.
- **Known issues / not done:** no email delivery (the administrator copies the
  link); no name search; the real-stack script is not in CI; staff accounts,
  class sessions and Zoom review screens remain (UI-3).
- **Next planned feature:** UI-3.

## Checkpoint UI-3a — 2026-11-11 — Staff screens: staff accounts and batch assignments

- **UI-2 evidence:** merged as PR #23, merge commit `4f876d9`; frontend CI green.
- **Feature ID / name:** UI-3a.
- **Branch:** `feature/staff-ui-team`. **PR / merge SHA:** see the merged pull
  request titled "feat(web): staff accounts and batch assignment screens".
- **Implemented behavior:** `/staff/team` lists staff accounts (you marked,
  roles senior-first, account and two-step status) with an add form;
  `/staff/team/{id}` changes roles (optimistic version), issues an invitation
  link while pending, and disables with an explicit confirmation. The screens
  mirror the API's rules: nobody changes or disables themselves; only owners
  grant Owner/Administrator or touch an owner's or administrator's account; the
  API re-checks every rule. Batch pages show an "Assigned staff" panel for
  managers to assign by email and remove assignments. The invitation form is now
  shared between students and staff. Nav gains "Staff".
- **API changes:** none. **Database changes:** none.
- **Tests executed (macOS):** frontend lint, type-check, 30 unit tests (4 new),
  production build; Playwright 38 tests (8 new: 4 scenarios × desktop and
  mobile), run twice. Real stack (`scripts/staff-ui-real-stack.sh`), extended:
  the owner adds an instructor, invites and assigns them; the instructor
  activates, signs in without MFA, sees only their batch and its roster, gets the
  manager-only banners; after removal the roster is 404 and the batch list is
  empty. Passed twice; staff assignments 0 at the end; no secrets in logs.
- **Test-only fixes before commit:** roster assertions scoped to the ROSTER
  region (the batch page now has two lists); the disable test checks the
  resulting page state because the form unmounts once the account is disabled;
  the real-stack spec waits for the batch URL before recording it.
- **Known issues / not done:** no email delivery; disabling is not reversible
  from the UI (the API has no re-enable); class sessions and Zoom review screens
  remain (UI-3b, UI-3c).
- **Next planned feature:** UI-3b.

## Checkpoint UI-3b — 2026-11-11 — Staff screens: class sessions

- **UI-3a evidence:** merged as PR #24, merge commit `02386c9`; frontend CI green.
- **Feature ID / name:** UI-3b.
- **Branch:** `feature/staff-ui-sessions`. **PR / merge SHA:** see the merged pull
  request titled "feat(web): class session screens".
- **Implemented behavior:** nav item "Classes". `/staff/sessions` lists classes
  in a fortnight (`?from=YYYY-MM-DD`, default today in Asia/Colombo; previous,
  next and "from today" links) with a batch filter, and for owners and
  administrators a schedule form (title, date, start and end time, one or more
  active batches, optional Zoom webinar ID). `/staff/sessions/{id}` shows the
  time in the class's zone, expected students (each once, with their batches),
  batches with roster links and the webinar ID; managers edit every field and
  cancel or restore. Assigned instructors and attendance officers read the
  classes of their batches; the batch page links to its classes.
- **Time handling:** people type a date and clock times in the class's zone
  (Asia/Colombo for new classes); the screen converts with `Intl` to UTC
  instants for the API and back for display, so daylight-saving zones also
  work (unit-tested with Europe/London). Classes must end on the day they
  start, a deliberate screen-only limit; the API allows up to 12 hours across
  midnight.
- **API changes:** none. **Database changes:** none.
- **Tests executed (macOS):** frontend lint, type-check, 33 unit tests (3 new),
  production build; Playwright 44 tests (6 new: 3 scenarios × desktop and
  mobile, including a stale edit refused with the typed values kept), run twice.
  Real stack: the owner schedules and edits a class, the assigned instructor
  sees it and its expected student, and gets 404 after unassignment; the
  database holds 12:30–15:00 UTC for 18:00–20:30 Colombo. Passed twice; no
  secrets in logs.
- **Defect caught before commit:** the "new class" panel heading and the title
  field shared an element id; the heading id was renamed.
- **Known issues / not done:** no recurring classes; no class time zone choice
  in the form (new classes use Asia/Colombo); editing a class's time or webinar
  ID does not revisit existing Zoom links (API behaviour, see 003D-1).
- **Next planned feature:** UI-3c Zoom review screens.

## Checkpoint UI-3c — 2026-11-11 — Staff screens: Zoom review

- **UI-3b evidence:** merged as PR #25, merge commit `5785fe6`; frontend CI green.
- **Feature ID / name:** UI-3c. With it, every staff API area has a screen.
- **Branch:** `feature/staff-ui-zoom`. **PR / merge SHA:** see the merged pull
  request titled "feat(web): Zoom review screen".
- **Implemented behavior:** nav item "Zoom review" (owners and administrators;
  others see an explanation). `/staff/zoom` lists webinar occurrences (All /
  Needs review / Decided; 50 most recent) with webinar ID, first and last event
  time in Colombo time, event count, state and the linked class. Undecided
  occurrences get a decision form: a class chosen from scheduled classes
  starting within a day (same webinar first, then nearest), or "Not a class",
  each with a required reason; decided ones can be changed. "Run automatic
  linking" reports how many were linked. Failed (dead-lettered) events show
  type, time, attempts and error, with a replay form and reason, plus a count of
  events waiting to be processed. Payloads are never shown (the API does not
  return them). The overview page's "Coming next" now names attendance only.
- **Design notes:** Zoom occurrence UUIDs may contain "/", so decisions are made
  in forms on the list page rather than on per-occurrence URLs; the occurrence
  id is checked for size and printable characters only. Class choices come from
  one class-list request spanning the shown occurrences (at most 92 days, the
  API's limit; older occurrences beyond that get no choices, only "Not a class").
- **API changes:** none. **Database changes:** none.
- **Tests executed (macOS):** frontend lint, type-check, 34 unit tests (1 new),
  production build; Playwright 50 tests (6 new: 3 scenarios × desktop and
  mobile), run three times after the fix below. Real stack: Zoom receipts and
  occurrences are seeded (no personal data; ingress and processing off);
  automatic linking links the occurrence on the class's webinar ("Linked 1
  occurrence; 1 still needs review."), the other is linked by hand then marked
  not a class, and the failed event is replayed into the queue. Database
  check: one AUTOMATIC link, one MANUAL "not a class", receipt back to RECEIVED
  with 0 attempts; audit has ZOOM_OCCURRENCE_LINKED,
  ZOOM_OCCURRENCE_MARKED_NOT_CLASS and ZOOM_RECEIPT_REPLAYED. Passed twice; no
  secrets in logs.
- **Test-only fix before commit:** the UI-3b stale-edit test opened the second
  tab before the first had navigated to the class (one timeout in a full run);
  it now waits for the class URL.
- **Known issues / not done:** no paging beyond the 50 most recent occurrences
  and failed events; no search by webinar ID; still unproven against a real
  Zoom account (003A).
- **Next planned feature:** 003A when the academy's Zoom test webinar is
  available; otherwise real-stack browser test in CI, or password recovery.

## Checkpoint CI-RS — 2026-11-11 — Real-stack browser test in CI

- **UI-3c evidence:** merged as PR #26, merge commit `707142f`; frontend CI green.
- **Feature ID / name:** CI-RS, real-stack browser test in CI.
- **Branch:** `ci/real-stack-browser-test`. **PR / merge SHA:** see the merged pull
  request titled "ci: run the real-stack browser test".
- **Change:** new workflow `.github/workflows/real-stack.yml` ("Real-stack browser
  test") on pushes and pull requests touching `backend/**`, `frontend/**`, the
  script or the workflow. It packages the backend jar (tests skipped; the backend
  workflow runs them), builds Next.js, and runs `scripts/staff-ui-real-stack.sh`
  on the Ubuntu 24.04 runner itself: PostgreSQL 17 through Docker and the Google
  Chrome GitHub preinstalls (Playwright `channel: "chrome"` when `CI` is set), so
  no apt-get and no browser download. On failure the script prints the last 40
  lines of the backend and frontend logs with this run's passphrase, invitation
  and any JWT masked, and the job keeps the Playwright trace for 3 days (it holds
  only throwaway per-run accounts).
- **Also:** `forbidOnly` in CI for the real-stack config; a duplicated sentence
  in IMPLEMENTATION-PLAN removed.
- **Tests executed:** the script with `CI=1` on macOS (passed); the masking
  pipeline checked by hand; the workflow's first runs are on the pull request.
- **Next planned feature:** password recovery with email delivery, or 003A once
  the academy's Zoom test webinar is available.

## Checkpoint 001J part 1 — 2026-11-12 — Password reset by email (API)

- **CI-RS evidence:** merged as PR #27, merge commit `5824af6`; the new real-stack
  job passed on its first runs ("1 passed", no secrets in logs).
- **Feature ID / name:** 001J part 1, self-service password reset (backend).
- **Branch:** `feature/001j-password-reset`. **PR / merge SHA:** see the merged
  pull request titled "feat(auth): password reset by email (API)".
- **Implemented behavior:** `POST /api/v1/auth/password-reset/request {email}`
  answers 202 with no body whatever the address (unknown, pending, disabled,
  malformed all look the same); only an active account gets an email, at most 3
  requests per account per hour (configurable), and a new request revokes the
  earlier link. The email (plain text, via any SMTP server) holds
  `<web origin>/reset-password#token=<256-bit token>`, valid 30 minutes, and is
  sent after the request has committed on a small bounded background queue, so
  the response does not wait for the mail server. `POST
  /api/v1/auth/password-reset/confirm {token, password}` claims the link
  atomically (single use), applies the password policy (a rejected password keeps
  the link usable), bumps the auth version, revokes every refresh session and
  clears any lockout. MFA is unchanged: owners and administrators still need
  their authenticator. Both steps are audited (PASSWORD_RESET_REQUESTED,
  PASSWORD_RESET_COMPLETED) with no address or token. Neither the address nor the
  link is ever logged.
- **Configuration:** off by default (request answers 503 with an explanation).
  `LUMA_PASSWORD_RESET_ENABLED=true` requires `LUMA_PASSWORD_RESET_FROM`,
  `LUMA_PASSWORD_RESET_WEB_ORIGIN` (https, or http only for localhost) and
  `SPRING_MAIL_HOST` (+ port, username, password); the backend refuses to start
  otherwise. Documented in `backend/.env.example` and the OpenAPI contract.
- **Database:** Flyway `V14__password_resets.sql` (digest only, one open reset
  per account, index for the hourly count). New dependency
  `spring-boot-starter-mail`.
- **Tests executed (macOS, JDK 21, Docker):** `mvn verify` 264 tests, 0
  failures (13 new: 9 HTTP integration with captured email, 1 disabled-mode, 3
  unit for settings and email text), also passing in random class order. Includes
  8 concurrent confirmations of one link → exactly one success.
- **Known issues / not done:** a request for an existing account does slightly
  more database work than for an unknown address, a small timing difference;
  rate limiting is per account only (no per-client limit yet); no browser pages
  yet (part 2); not yet sent through a real SMTP server (part 2 adds Mailpit to
  the real-stack test). No email provider is chosen or provisioned: that is the
  academy's decision.
- **Next planned feature:** 001J part 2, the `/forgot-password` and
  `/reset-password` pages and a real SMTP run.

## Checkpoint 001J part 2 — 2026-11-12 — Password reset pages and real SMTP run

- **Part 1 evidence:** merged as PR #28, merge commit `4a769b7`; Linux backend CI
  264 tests, 0 failures; real-stack job green.
- **Feature ID / name:** 001J part 2. With it, 001J is complete.
- **Branch:** `feature/001j-reset-pages`. **PR / merge SHA:** see the merged pull
  request titled "feat(web): forgot and reset password pages".
- **Implemented behavior:** "Forgot your password?" on the sign-in form opens
  `/forgot-password`, which posts to the BFF action `reset-request` (origin
  checked like sign-in) and always shows the same "if an active account uses
  that address" message; the address is never put in a URL. When email is not
  configured it explains that an administrator can help. `/reset-password`
  reads the code from the link's `#token=` fragment and removes it from the
  address bar (pasting the code or whole link also works); `reset-confirm`
  checks the code shape, matching passwords and length first, keeps the code in
  the fragment after a mistake, and on success redirects to `/sign-in?reset=1`
  ("signed out everywhere"). A used or expired link offers a new one.
- **Real-stack test:** the script now runs Mailpit (`axllent/mailpit:v1.27.0`)
  as the SMTP server with reset enabled. The student asks for a link; the test
  reads the email from Mailpit (subject, sender, link to the web origin), sets a
  new password through the link, confirms the old password fails and the new
  one signs in, and that the link is single use; an unknown address gets the
  same page and no email. Database: one reset, used; audit has
  PASSWORD_RESET_REQUESTED and PASSWORD_RESET_COMPLETED; exactly one email
  caught; logs checked for passphrases, invitations, JWTs, reset links and the
  student's address: none. Passed three times locally after the fix below.
- **Tests executed (macOS):** frontend lint, type-check, 34 unit, build, HTTP
  smoke; Playwright 56 tests (6 new: 3 scenarios × desktop and mobile) run twice.
- **Test-only fix before commit:** the real-stack step typed into `#email`
  before the forgot-password page had replaced the sign-in page (both have that
  id), so the form was empty and the browser blocked it; it now waits for the
  heading. The first runs failed this way; none after.
- **Known issues / not done:** no email provider chosen or provisioned (the
  academy decides; any SMTP service works); invitations are still copied by the
  administrator rather than emailed; rate limiting is per account only.
- **Next planned feature:** emailing invitation links, or 003A when the
  academy's Zoom test webinar is available.

## Checkpoint 001K — 2026-11-12 — Emailed invitations

- **001J evidence:** merged as PR #28 (`4a769b7`) and PR #29 (`46c8701`); CI
  green, real-stack CI caught one reset email.
- **Feature ID / name:** 001K, emailed invitations.
- **Branch:** `feature/001k-invitation-email`. **PR / merge SHA:** see the merged
  pull request titled "feat: email invitation links".
- **Settings renamed (nothing was deployed with the old names):** email is now
  one shared setting for invitations and resets: `LUMA_MAIL_ENABLED`,
  `LUMA_MAIL_FROM`, `LUMA_MAIL_WEB_ORIGIN` (+ `SPRING_MAIL_*`). The reset-only
  `LUMA_PASSWORD_RESET_ENABLED/FROM/WEB_ORIGIN` named in checkpoint 001J part 1
  no longer exist; `LUMA_PASSWORD_RESET_TTL` and `_MAX_REQUESTS_PER_HOUR` remain.
- **Implemented behavior:** `POST /api/v1/accounts/{id}/invitations` accepts an
  optional `{"delivery": "LINK" | "EMAIL"}` (LINK is the default, also with no
  body, so existing callers are unchanged). EMAIL emails
  `<web origin>/activate#token=…` (valid 72 hours) to the account's address after
  the change commits and returns no token or path, so the link reaches only the
  invitee; 503 "Email is not set up…" when email is off, before anything changes.
  The audit record gains `delivery`. Staff and student record pages show "Email
  invitation" (then "Email it again") beside "Create invitation link".
- **Code:** `AccountMailer` (invitation and reset emails), `SmtpAccountMailer`,
  `MailDelivery` (after-commit bounded queue), `MailProperties`, and a shared
  `ServiceUnavailableException` mapped to 503 for every controller.
- **Tests executed (macOS, JDK 21, Docker):** backend `mvn verify` 269 tests, 0
  failures (5 new), also in random class order. Frontend lint, type-check, 34
  unit, build; Playwright 58 (2 new) run twice. Real stack: the instructor's
  invitation is now emailed through Mailpit and the test activates from the
  email (the student's stays a copied link, so both paths run); 2 emails caught;
  logs checked for activation and reset links and both addresses: none. Passed
  three times.
- **Known issues / not done:** no email provider chosen or provisioned; no
  bounce or delivery tracking (an emailed invitation that never arrives looks the
  same as one not yet opened; the administrator can email again or create a link).
- **Next planned feature:** 003A when the academy's Zoom test webinar is
  available; otherwise per-client rate limiting on public auth routes, or a
  first-owner provisioning command.

## Checkpoint 001L — 2026-11-12 — Per-visitor limits on the public auth routes

- **001K evidence:** merged as PR #30, merge commit `0904650`; all CI green.
- **Feature ID / name:** 001L, per-client rate limiting.
- **Branch:** `feature/001l-auth-rate-limits`. **PR / merge SHA:** see the merged
  pull request titled "feat(auth): per-visitor limits on public auth routes".
- **Implemented behavior:** a servlet filter ahead of Spring Security counts POSTs
  to `/auth/login` (60 per 5 min), `/auth/mfa/enroll` and `/auth/mfa/verify` (60 per
  5 min together), `/auth/activate` and `/auth/password-reset/confirm` (30 per 10
  min) and `/auth/password-reset/request` (10 per hour) per visitor address, and
  answers 429 with `Retry-After` and a problem detail beyond that. All limits are
  configurable (`LUMA_RATE_LIMIT_*`) and can be switched off. Counters are fixed
  windows in an UNLOGGED PostgreSQL table (Flyway V15), so every API instance
  counts together; rows older than a day are deleted hourly. IPv4 addresses are
  counted as is, IPv6 per /64. If the counter store fails the request is allowed
  (fail open) and a warning logged; per-account lockouts still apply. Addresses
  are never logged. Refresh, logout and authenticated routes are not limited here.
- **Client address:** the API uses Tomcat's RemoteIpValve
  (`server.forward-headers-strategy: native`): `X-Forwarded-For` is honoured only
  from `LUMA_TRUSTED_PROXIES` (regex; default loopback), so a forged header from
  anyone else is ignored (tested). The Next.js BFF passes the visitor's address on
  every public auth call, read `LUMA_TRUSTED_PROXY_HOPS` entries from the right of
  its own `X-Forwarded-For` (default 0: nothing forwarded). Production needs both
  set: hops = number of load balancers in front of Next.js, and the API's trusted
  proxies = the Next.js servers' addresses.
- **Why generous defaults:** many students reach the internet through mobile
  carrier NAT, sharing one public address; the limits slow spraying and email
  bombing without blocking a class signing in together. Revisit with real traffic.
- **Tests executed (macOS, JDK 21, Docker):** backend `mvn verify` 279 tests, 0
  failures (10 new: limit and Retry-After, other clients unaffected, separate
  buckets, unlimited routes, reset requests limited even when email is off, IPv6
  /64, 10 concurrent attempts → exactly 3 allowed, cleanup, untrusted forwarding
  ignored, address parsing), also in random class order. Frontend lint,
  type-check, 36 unit tests (2 new for the address helper), build, smoke;
  Playwright 58 run twice. Real stack: Next.js with one trusted hop; two
  "visitors" (203.0.113.7 and .8) each get 3 reset requests and the first one's
  4th is refused; the database shows their separate counters. Passed twice.
- **Known issues / not done:** fixed windows allow up to twice the limit across a
  window boundary; no allow-list for the academy's own network; limits are per
  address, so a large botnet is only slowed by the per-account lockouts.
- **Next planned feature:** 003A when the academy's Zoom test webinar is
  available; otherwise a first-owner provisioning command.

## Checkpoint 001M — 2026-11-12 — First-owner operator command

- **001L evidence:** merged as PR #31, merge commit `98e36a7`; all CI green.
- **Feature ID / name:** 001M, first-owner provisioning.
- **Branch:** `feature/001m-first-owner`. **PR / merge SHA:** see the merged pull
  request titled "feat: first-owner operator command".
- **Implemented behavior:** `scripts/bootstrap-owner.sh "Academy" owner@x [PRINT|EMAIL]`
  starts the backend jar without a web server and runs `OwnerBootstrapService`: creates
  the academy if no academy has that exact name, creates a pending owner, issues an
  activation invitation (72 h), prints the link to standard output only or emails it,
  and exits (0 done, 1 refused, 2 bad arguments, 3 email failed). Re-running before
  activation reissues the link (old one cancelled). Refuses: an active owner exists, a
  pending owner invitation for another address, an address used by another account, two
  academies with the name. Serialised with a PostgreSQL advisory lock. Audited as
  OWNER_BOOTSTRAPPED with no actor. No HTTP route. Runbook:
  `docs/delivery/runbooks/FIRST-OWNER.md`.
- **Also:** `SecurityConfig` now loads only in a servlet web application (the command
  runs without one; a running server is unchanged).
- **Defects caught by the real-stack run, fixed before commit:** Spring could not choose
  between the command's two constructors (the test one and the Spring one) — the unit
  tests construct it directly, so only a real run showed it; and Spring Security's web
  configuration failed without a web server. Lesson for agents: run new entry points for
  real, not only through their tests.
- **Tests executed (macOS, JDK 21, Docker):** backend `mvn verify` 287 tests, 0 failures
  (8 new: new academy and owner end to end with MFA required, re-run reissues, active
  owner never replaced, conflicts refused and rolled back, existing academy without
  owner, 6 concurrent runs → one academy and one owner, printed link never in the log,
  email path and its failures), also in random class order. The command run twice by
  hand against a throwaway database (created, then reissued). Real stack now seeds the
  academy and owner through the command (no SQL for them), checks the link appears once
  in the output, and that a second run after activation exits 1 ("already has an active
  owner"); passed three times.
- **Known issues / not done:** no automated recovery when every owner has lost both
  password and authenticator (documented as needing a reviewed manual procedure).
- **Next planned feature:** 003A when the academy's Zoom test webinar is available;
  otherwise deployment infrastructure (needs the academy's authorisation for cloud
  resources) or the load-test plan.

## Checkpoint PERF-1 — 2026-11-12 — Load-test plan, scripts and a local baseline

- **001M evidence:** merged as PR #32, merge commit `884725b`; all CI green.
- **Feature ID / name:** PERF-1. **No capacity claim:** LUMA is not stated to support
  1,500 users; the gate is a production-like run (docs/testing/LOAD-TEST-PLAN.md §8).
- **Branch:** `feature/perf-load-test-plan`. **PR / merge SHA:** see the merged pull
  request titled "test: load-test plan, k6 scenarios and local baseline".
- **Delivered:** `docs/testing/LOAD-TEST-PLAN.md` (assumptions for the academy to
  confirm, targets, scenarios L1–L8 with what is blocked, environments, data, risks, exit
  criteria, local baseline); k6 scripts in `load-tests/k6` (sign-in burst through the BFF,
  signed webhook bursts, instructor reads with session refresh); `scripts/load-test-local.sh`
  (seeds the owner with `bootstrap-owner.sh` and 1,500 synthetic students via SQL with one
  real Argon2id hash, runs k6 in Docker, measures worker drain and CPU); a manual GitHub
  workflow "Load test (local baseline)"; `LUMA_ZOOM_PROCESSING_BATCH_SIZE` setting exposed.
- **Local baseline (one M4 laptop, 1,500 students, all scenarios):** sign-in p95 62 ms,
  portal p95 11 ms, webhook ack p99 14 ms with 0 failures, 3,600 receipts processed once
  each with no backlog, staff class page p95 446 ms; API peak ~1 core. 182 connection
  timeouts (3.1% of student requests) before any HTTP exchange: suspected macOS listen
  backlog / Docker Desktop forwarding, **unexplained until reproduced or cleared on Linux**.
  35 staff redirects came from the first script not refreshing sessions; fixed and re-run
  with 1-minute tokens (40 refreshes, 0 failures).
- **Next:** run the manual workflow on a Linux runner to settle the connection timeouts
  (uses GitHub Actions minutes), then the production-like gate run once cloud resources
  are authorised.

## Checkpoint PERF-1a — 2026-11-12 — Linux runner baseline, 300 students

- **PERF-1 evidence:** merged as PR #33, merge commit `180edb3`.
- Ran the manual "Load test (local baseline)" workflow at the owner's request with 300
  students (run 43194535303, success): 0 failures in all scenarios, no connection timeouts,
  sign-in p95 265 ms, portal p95 64 ms, webhook ack p99 148 ms, 720 receipts processed
  once with no backlog, staff class page p95 463 ms, API peak about 1.5 cores, no ERROR
  lines. Recorded in LOAD-TEST-PLAN.md §9.
- **Still open:** the 182 timeouts of the 1,500-student laptop run are not settled by a
  300-student run (the laptop had none at 150 either); needs a 1,500-student Linux run.
  Still no capacity claim.

## Checkpoint PERF-1b — 2026-11-12 — Linux runner, 1,500 students

- **PERF-1a evidence:** PR #34, merge commit `9b5164e`.
- Ran the manual load workflow at the owner's request with 1,500 students (run
  43195499238). **The run failed its latency targets** on one 4-vCPU runner hosting k6, the
  API, Next.js and PostgreSQL: sign-in p95 6.93 s (target 2 s), portal p95 6.56 s (target
  0.5 s), staff class page p95 3.79 s, with 2 HTTP 500s and one "couldn't load" class page.
  Webhooks met their target (ack p99 132 ms, 3,600 processed once, no backlog). Peak CPU:
  API 168%, web 56%, database 63%. The limiting tier was not isolated.
- **Closed:** the laptop's 182 connection timeouts did not occur on Linux (0 of 6,000
  student requests failed): local harness, not LUMA.
- **Script fixes:** the staff script now clears cookies before signing in again (it cascaded
  into 227 failed sign-ins after the first page errors; reasoned fix, not yet exercised at
  1,500); the local runner prints a masked summary of web-log errors (the two 500s came from
  the web tier, whose log was not captured).
- **Next:** load generator on a separate machine, per-tier timings, a larger Hikari pool,
  then the production-like gate run (needs cloud authorisation). Still no capacity claim.

## Checkpoint PERF-2 — 2026-11-12 — Bottleneck isolated; N+1 query fixed

- **PERF-1b evidence:** PR #35, merge commit `b55f11d`.
- **Branch:** `perf/isolate-bottleneck`. **PR / merge SHA:** see the merged pull request
  titled "perf: fix the roles N+1 query and add per-tier load-test measurement".
- **Method:** each flow measured alone on the laptop with new instrumentation in
  `scripts/load-test-local.sh` (CPU seconds per tier, API access log with method/path/
  status/ms only, `pg_stat_statements`, sampled busy connections; `DB_POOL` setting).
- **Fixed:** an N+1 query. Rosters and the class page loaded each student's account and
  then that account's roles one query at a time (522,020 role queries in 3 minutes of staff
  reads). `hibernate.default_batch_fetch_size: 100` cut it to 5,260; class page p50 320 →
  84 ms, class-students API p50 290 → 46 ms, database CPU 19.2 → 8.4 s. 287 backend tests pass.
- **Ruled out:** the 10-connection database pool (at most about 2 ms of database time per
  request; under 1% busy at peak).
- **Explained:** the Linux run was CPU-saturated. Peak app demand is about 0.9 M4 cores (API
  0.56, web 0.22, DB 0.09; sign-ins 45%, staff pages 41%, portal 14%); the runner used about
  3× that per tier (~2.9 of 4 vCPUs) alongside three k6 processes. Argon2id cost is kept and
  sized for. Details and sizing estimates in LOAD-TEST-PLAN.md §10. Still no capacity claim.

## Checkpoint PERF-2a — 2026-11-12 — 1,500-student Linux re-run after the N+1 fix

- **PERF-2 evidence:** PR #36, merge commit `b35019d`.
- Re-ran the manual load workflow at the owner's request (run 43209730397, 1,500 students,
  all scenarios, one 4-vCPU runner): **every target met, no failures.** p95 before → after:
  sign-in 6.93 s → 144 ms, portal 6.56 s → 48 ms, staff class page 3.79 s → 335 ms, roster
  2.53 s → 143 ms; webhook ack p99 26 ms; 3,600 receipts processed once; database peak CPU
  63% → 15%; no ERROR lines in either log.
- The N+1 fix mattered more than the laptop model predicted (it counted only database CPU;
  on the runner the extra round trips also loaded the API and the shared machine). Recorded
  in LOAD-TEST-PLAN.md.
- **Still not the capacity gate** (needs a production-like environment with a separate load
  generator, the 60-minute soak, and the live-attendance scenarios once built). No capacity claim.

## Checkpoint 003A-prep — 2026-11-12 — Zoom test tunnel and webinar allow-list

- **Context:** the owner chose a temporary tunnel to this Mac for the first real Zoom test,
  and kept the 1,500-student target. A read-only look at the Zoom account (owner's Chrome)
  found Zoom Webinars for 500 attendees, registration with sign-in on webinars, one existing
  Server-to-Server OAuth app ("BulkRegister", not reused), and recurring live classes on most
  days. Nothing was changed in Zoom.
- **Implemented:** `LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST` — when set, only events for those
  webinar IDs are stored; others are acknowledged and dropped unread (not stored, not
  logged), because a Zoom event subscription covers the whole account and real classes must
  not be collected before the privacy sign-off. `scripts/zoom-test-tunnel.sh`: PostgreSQL
  (kept volume), backend and web app on 127.0.0.1 only, nginx passing only
  `POST /api/v1/zoom/webhooks`, Cloudflare quick tunnel over HTTP/2; refuses to start without
  the allow-list; secrets read from gitignored `.env.zoom-test`. Runbook:
  `docs/integration/ZOOM-TEST-WEBINAR.md`.
- **Tests:** 2 new integration tests (allow-list stores only listed webinars; dropped
  payloads and their IDs not logged; ID validation); all webhook tests pass. End-to-end over
  the live tunnel with a throwaway token: other paths 404, validation signed correctly,
  allowed event stored and processed, other webinar not stored, unsigned 401. First attempt
  failed because cloudflared's default QUIC never registered; HTTP/2 fixed it.
- **Next:** create the dedicated Zoom app with the owner (Chrome), owner adds the secret token,
  validate the URL, run a test webinar with test participants only.

## Checkpoint PERF-3 — 2026-11-12 — Target 600 concurrent users; Zoom app and env template

- **003A-prep evidence:** PR #38, merge commit `506b550`; CI green (289 backend tests).
- **Academy decision:** one webinar at a time, at most 500 students; capacity target **600
  concurrent users** (was 1,500). LOAD-TEST-PLAN, k6 defaults (600), the local runner's window
  scaling (plan windows at 600) and the manual workflow default (600) updated;
  OPEN-QUESTIONS records the partial answer. Earlier 1,500 runs kept as recorded (2.5× headroom).
- **600-student local baseline:** every latency target met (sign-in p95 82 ms, portal 17 ms,
  class page 96 ms, webhook ack p99 23 ms, 1,440 receipts processed once); 23 local-harness
  connection timeouts. Not capacity evidence.
- **Zoom (owner's Chrome, owner's request):** created Server-to-Server OAuth app "LUMA
  Attendance Test" (account level, not for publishing; separate from "BulkRegister"),
  Information filled with the academy's details, and four read-only scopes:
  `webinar:read:webinar:admin`, `webinar:read:list_past_instances:admin`,
  `webinar:read:list_past_participants:admin`, `webinar:read:list_registrants:admin`. Not
  activated; no event subscription yet. The credentials and Features pages were not read
  (live secrets). An app name with parentheses failed silently in Zoom's create dialog.
- **Secrets:** `.env.zoom-test.example` (tracked template) and `scripts/zoom-test-setup.sh`,
  which writes the gitignored `.env.zoom-test` from hidden prompts (mode 600, refuses if the
  file is not gitignored). The assistant does not handle the live values; the owner pastes them.
- **Next:** owner stores the secrets; then activate the app and add the event subscription
  (tunnel URL, the four webinar events), with a test webinar on the allow-list.

## Incident 003A-1 — 2026-11-12 — Zoom webhook Secret Token exposed in an assistant transcript

- **What happened:** while preparing the event subscription, an element search on the Zoom
  app's Features page returned the Secret Token, because Zoom includes it in the token
  field's accessible label. The value entered the assistant's transcript. Earlier the same
  day, stopping the tunnel tab returned its last lines, including the local test owner's
  activation link (only usable on 127.0.0.1 on this Mac; superseded on the next start).
- **Impact:** the token lets someone forge webhook events that LUMA's test endpoint would
  accept; it grants no access to the Zoom account. The client secret was not exposed. No event
  subscription existed yet and the tunnel was stopped at once.
- **Response:** owner to regenerate the Secret Token in Zoom and store the new one with
  `scripts/zoom-test-setup.sh`; the tunnel script now writes the owner link to a gitignored
  file instead of the screen; the runbook forbids automated reads of the Features and App
  Credentials pages. Created on the same day: test webinar "LUMA test - do not join"
  (838 3442 8148, 13 Nov 2026 11:00 Colombo, registration and sign-in required), app
  "LUMA Attendance Test" activated with four read-only scopes.

## Checkpoint 003A-2 — 2026-11-12 — Real Zoom validation passed; event subscription live

- **Incident 003A-1 resolved:** the webhook Secret Token was regenerated in Zoom (owner's
  request, by the assistant without viewing the new value: Regenerate, Copy, clipboard piped
  into `.env.zoom-test` with only its length and fingerprint checked, clipboard cleared). The
  exposed token no longer works.
- **First real Zoom contact:** with `scripts/zoom-test-tunnel.sh` running, Zoom's
  "Validate" on the event subscription endpoint returned **Validated**: LUMA answered Zoom's
  signed `endpoint.url_validation` with the HMAC of the new token. This is the first time a
  real Zoom signature, not a fixture, was verified.
- **Event subscription saved** on app "LUMA Attendance Test": name "LUMA test webinar events",
  method Webhook, the tunnel URL, events Start Webinar, End Webinar, Participant/Host joined
  webinar, Participant/Host left webinar; receiver "All users in the account". Allow-list:
  test webinar 838 3442 8148 (13 Nov 2026 11:00 Colombo). Every other webinar's events are
  acknowledged and dropped unread.
- **Lessons for agents:** on the Zoom Features page, element searches and page-text reads can
  return the Secret Token; scroll actions return automatic screenshots; the Chrome extension
  redacts result keys containing "token". Use cropped zooms below the token row, scrolling
  by script.
- **Open:** the tunnel URL changes on each start, so the subscription's URL must be updated
  and re-validated after any restart; keep the tunnel running through the test or restart it
  before 11:00 on 13 Nov. After the test, stop the tunnel and switch the subscription off.

## Checkpoint 003A-3 — 2026-11-12 — Zoom API client verified against the real account

- **003A-2 evidence:** PR #41, merge commit `e94599b`; by 20:11 Colombo 147 real events from
  live classes passed signature verification and were dropped by the allow-list (0 stored).
- **Branch:** `feature/003a-zoom-api-client`. **PR / merge SHA:** see the merged pull request
  titled "feat(zoom): read-only Zoom API client with an operator check".
- **Implemented:** `ZoomApiClient` (Server-to-Server OAuth): token from
  `POST https://zoom.us/oauth/token?grant_type=account_credentials&account_id=…` with Basic
  client credentials, reused until a minute before its 1-hour expiry, one refetch on 401;
  `GET /webinars/{id}?show_previous_occurrences=true`, `GET /past_webinars/{id}/instances`,
  `GET /past_webinars/{id or uuid}/participants` and `GET /webinars/{id}/registrants` with
  `next_page_token` paging (300 per page); UUIDs starting with "/" or containing "//" are
  double-encoded; errors carry Zoom's code and Retry-After, never credentials; participant and
  registrant records print no personal data. Endpoints, fields and scopes were checked against
  Zoom's API reference first. Off unless `LUMA_ZOOM_API_ACCOUNT_ID/_CLIENT_ID/_CLIENT_SECRET`
  are all set.
- **Operator check:** `scripts/zoom-api-check.sh` (jar without a web server, throwaway DB,
  credentials from `.env.zoom-test`). Against the real account: token obtained; scopes granted
  were the four read-only webinar scopes plus `webinar:read:participant:admin` (added by Zoom
  for the participant events); the test webinar read correctly (type 5, 05:30Z, 60 min,
  auto-approve); 0 registrants; no past instances yet. A live class's ID was refused before any call.
- **Tests:** 8 new against a fake Zoom server (token reuse and renewal, single 401 retry,
  webinar and occurrences, paging, double-encoding, 429 Retry-After, bad credentials, no
  identities printed by the check). Backend `mvn verify` 297 tests, 0 failures, also in random
  order.
- **Environment note:** the user's global `~/.testcontainers.properties` was rewritten at 19:13
  by something outside this project (docker.raw.sock), breaking every Testcontainers test
  locally; not edited by the assistant; tests run with `DOCKER_HOST=unix:///var/run/docker.sock`.
  CI is unaffected.
- **Next:** test webinar on 13 Nov 11:00; then compare webhook events with the API's
  participants and registrants to design participant-to-student matching (003D part 2).

## Checkpoint STU-1 — 2026-11-12 — Student portal: my classes and batches

- **Branch:** `feature/student-portal-classes`. **PR / merge SHA:** see the merged pull request
  titled "feat(student): student portal shows the student's classes and batches" (PR #43, merge commit `a737ad0`; all CI checks green, including the real-stack job with the new student step).
- **Implemented:** `GET /api/v1/me/class-sessions?from&to` (STUDENT role, window at most 92
  days). A class is listed when the student is enrolled, on the class's local date, in at least
  one of its batches; only those batches are named; cancelled classes are included; the Zoom
  webinar ID is not returned. The student portal (`/student`) now shows "My classes" in
  four-week pages (times in each class's own zone) and "My batches" with enrolment and
  withdrawal dates, replacing the placeholder. Attendance history is still to come.
- **Tests:** backend `MyClassSessionHttpIntegrationTest` (2 tests over real HTTP: before
  enrolment, shared class, after withdrawal, other batch, cancelled, exact response fields,
  other students, staff 403, anonymous 401, long or reversed window 400). Playwright
  `student-portal.spec.ts` (desktop and mobile; 62 mock-backend tests pass). The real-stack
  test now signs the activated student in again and checks the class scheduled by the
  administrator appears in their portal (run in CI, not locally: the Zoom test tunnel is
  running from the local backend jar).
- **Tooling:** `scripts/backend-test-docker.sh` runs the backend Maven build in a throwaway
  `maven:3.9.9-eclipse-temurin-21` container (source mounted read-only, Maven cache in the
  `luma-m2-cache` volume, Testcontainers through the host Docker socket), so tests run without
  touching the host JDK, `target/`, or other running processes. 297 backend tests passed with it.

## Checkpoint STU-2 — 2026-11-12 — Bulk student import from CSV

- **Branch:** `feature/student-import`. **PR / merge SHA:** see the merged pull request titled
  "feat(students): bulk student import from CSV" (PR #45, merge commit `72007a8`; all CI checks green, including the real-stack import step).
- **Implemented:** `POST /api/v1/students/import` (owner/admin; 1–1,000 rows; optional
  `batchId` + `enrolledOn`; `dryRun`). Every row is checked before anything is written: number
  format, name, email, repeats within the file, numbers already in the academy, emails already
  used by any account. Any problem → nothing written, every problem returned by row. Accounts
  are created pending activation (no invitations sent), inserted in JDBC batches
  (`hibernate.jdbc.batch_size: 100`). Audit event `STUDENTS_IMPORTED` with counts and batch only.
  Staff screen `/staff/students/import`: the CSV is read in the browser (columns found by name;
  quotes, commas, line breaks, BOM handled), "Check file" shows problems by the file's own line
  numbers, and "Import N students" appears only after a clean check of the same file, batch and date.
- **Measured:** 600 students with enrolments imported in about 300 ms in the integration test
  (Testcontainers PostgreSQL in Docker on the Mac; not a capacity claim).
- **Tests:** backend `StudentImportHttpIntegrationTest` (dry run writes nothing; 600-row import
  with enrolments, roles and audit without personal data; same file again → every row clashes;
  10 problem cases by row with nothing written; 403/401/400/404/409 refusals). Frontend unit
  tests for the CSV reader; Playwright `student-import.spec.ts` (desktop and mobile); real-stack
  test imports two students into the batch after a refused clash.
- **Privacy:** the tool exists, but real student data still waits for the privacy questions
  in `docs/product/OPEN-QUESTIONS.md` to be signed off.

## Checkpoint STU-3 — 2026-11-12 — Bulk invitation emails

- **Branch:** `feature/bulk-invitations`. **PR / merge SHA:** see the merged pull request titled
  "feat(students): bulk invitation emails with a durable send queue" (PR #47, merge commit
  `e4fa841`; all CI checks green; real-stack "1 passed", "secrets in logs: 0").
- **Why a queue:** single invitations are emailed through an in-memory queue of 100 that drops
  messages when full and loses them on restart. Fine for one at a time; 500 at once would
  silently lose most. Bulk invitations therefore use a database queue (V16 `invitation_emails`).
- **Implemented:** `POST /api/v1/student-invitations` (`batchId` optional, `resend`, `dryRun`)
  and `GET /api/v1/student-invitations/progress`. A background worker (`luma.mail.bulk.*`; default
  20 emails every 5 s) claims each queued email with `FOR UPDATE SKIP LOCKED`, issues a new link in
  that transaction (the token is never stored), sends after commit, then marks it sent or
  retries with back-off (1, 2, 4, 8 min; failed after 5 attempts). At least once: a worker that
  dies after sending leaves the email due when its 10-minute lease ends, and the next link
  replaces the first. Accounts activated meanwhile are cancelled, not emailed. Only pure student
  accounts of the caller's academy that are pending activation are reached. Audit:
  `INVITATIONS_QUEUED` (counts) and `INVITATION_ISSUED` per link (no addresses or links).
  Batch page and students page get an "Invite by email" panel: check who will be emailed, then
  send; progress refreshes while emails wait.
- **Also fixed:** request bodies with an omitted boolean (`dryRun`) were refused with 400 by
  Jackson's primitive handling; the student import endpoint had the same latent issue.
- **Tests:** backend `StudentInvitationHttpIntegrationTest` (6 tests: batch selection with
  withdrawn, inactive, other-batch and activated students; dry run; no double queueing; emailed
  links activate; resend semantics; staff never reached; retry then give up then re-queue;
  cancellation on activation; three concurrent senders email 30 students exactly once;
  refusals) plus 503 when email is off. Playwright `bulk-invitations.spec.ts` (desktop and
  mobile). Real-stack: the worker emails two imported students through Mailpit and the link
  activates one of them.

## Checkpoint SES-2 — 2026-11-12 — Weekly class series

- **Branch:** `feature/class-series`. **PR / merge SHA:** see the merged pull request titled
  "feat(sessions): weekly class series with preview and overlap checks" (PR #49, merge commit
  `83a5f5c`; all CI checks green).
- **Implemented:** `POST /api/v1/class-sessions/series` (title, first and last date, weekdays,
  local start and end time, zone, batches, webinar ID, dates to skip, `dryRun`): 1–100 classes
  within a year, each an ordinary class session sharing a `series_id` (V17). Local times follow
  the zone's daylight-saving changes. A class that would overlap a scheduled class of any of the
  same batches is reported and nothing is created (cancelled classes, other batches and
  back-to-back classes do not clash). `POST …/series/{id}/cancellations {fromDate}` cancels the
  series' scheduled classes from a date. Classes page: "Schedule a weekly series" form (preview
  list, overlaps linked to the existing class, Create only after a clean preview of the same
  details). Class page: "Cancel the rest of the series".
- **Tests:** backend `ClassSeriesHttpIntegrationTest` (5 tests: preview vs create, overlap rules,
  cancel from a date and refusals, Europe/London across the March 2027 clock change, validation).
  Unit tests for the series form. Playwright `class-series.spec.ts` (desktop and mobile); three
  existing specs now pick batch checkboxes inside the single-class form, since the classes page
  has two batch lists. Real-stack test creates a two-class series on the real API.
- **Not covered:** editing a whole series at once (each class is edited alone); changes to a
  class's time or webinar ID still do not revisit existing Zoom links.

## Checkpoint STU-4 — 2026-11-12 — Student search

- **Branch:** `feature/student-search`. **PR / merge SHA:** see the merged pull request titled
  "feat(students): search the directory by name, number or email" (PR #50, merge commit
  `9244a08`; all CI checks green).
- **Implemented:** `GET /api/v1/students?q=` — part of a student number or full name, any
  letter case (`%` and `_` matched literally), or a whole email address (never part of one, so
  addresses cannot be guessed letter by letter); combines with the active filter and keyset
  paging; at most 100 characters. Students page: "Find a student" box kept across the status
  filter and pages, with Clear.
- **Tests:** backend search test (case, partial number, literal `%`/`_`, whole email only, other
  academy excluded, paging, active filter, 400, 403); Playwright `student-search.spec.ts` (desktop
  and mobile); real-stack search by name fragment and by email on PostgreSQL.

## Checkpoint ZM-5 — 2026-11-12 — Zoom links follow the timetable

- **Branch:** `feature/zoom-link-revision`. **PR / merge SHA:** see the merged pull request titled
  "feat(zoom): re-check automatic Zoom links when a class changes" (PR #51, merge commit
  `9a1903c`; all CI checks green).
- **Problem:** an automatic occurrence-to-class link was made once and never revisited, so moving a
  class, changing its webinar ID or cancelling it left Zoom events (and later attendance) on the
  wrong class; and occurrences that arrived before a class existed waited for "Run automatic linking".
- **Implemented:** the class module publishes `ClassSessionChanged` (with the class's webinar and
  time before and after) inside the transaction of every create, edit, series create and series
  cancellation. The Zoom module's `ClassLinkReviser` handles it in the same transaction. It
  considers the class's automatic links and every occurrence on its webinar whose first event lies
  within 60 minutes of its old or new time. An automatic link is kept only while the matcher would
  still make it (exactly one scheduled class fits, and it is the linked one); otherwise it is removed
  and matched again, moving to the one class that now fits or back to review. Undecided occurrences
  are matched again. Manual decisions are never changed. Changes are audited as `ZOOM_LINKS_REVISED`
  (counts only). V18 indexes occurrence events by webinar. The class page lists the Zoom occurrences
  linked to it; the save message and the Zoom page explain the re-check.
- **Behaviour change:** a class scheduled after its Zoom events arrived links them immediately;
  "Run automatic linking" remains for anything left undecided. The real-stack and backend tests that
  expected the old timing were updated.
- **Tests:** backend `ClassLinkRevisionIntegrationTest` (5 tests: move away and back, webinar
  change, cancel and restore, a clashing new class then cancelling one, manual decisions untouched,
  series picking up earlier events and releasing them on cancel-the-rest); Playwright Zoom spec moves
  a class and sees its occurrence return to review; real-stack sees the link appear when the class
  is scheduled.
- **Not covered:** a manual link to a class that is later cancelled stays (an administrator's
  decision); the review page lists it as linked by an administrator.

## Checkpoint 001N — 2026-11-13 — Re-enable staff accounts

- **Branch:** `feature/staff-reenable`. **PR / merge SHA:** see the merged pull request titled
  "feat(staff): re-enable disabled staff accounts" (PR #52, merge commit `992dcf0`; all CI
  checks green).
- **Implemented:** `POST /api/v1/staff-accounts/{id}/enable {requireNewPassword}` under the same
  rules as disabling (only an owner for owner or administrator accounts; staff of the caller's
  academy only, never students). The account returns ACTIVE with its existing password and
  two-step sign-in, or to PENDING_ACTIVATION (old password removed, open invitations revoked) when
  a new password is required or it never had one. Sessions and tokens ended by the disable stay
  invalid; lockout is cleared; `STAFF_ACCOUNT_ENABLED` is audited with the outcome only. Staff
  view gains `passwordSet` (whether one exists, nothing more). Staff record page: "Re-enable
  account" with an optional "make them choose a new password"; the disable form no longer says it
  cannot be undone.
- **Tests:** 4 backend tests (same password signs in while old tokens and refresh stay dead;
  new-password path through a fresh invitation; disabled-before-activation returns to pending and
  the old invitation is dead; owner-only for administrators, other academies 404, students 404).
  Playwright team spec on desktop and mobile.
- **Environment note:** overnight the Mac slept; Cloudflare dropped the quick tunnel ("Tunnel not
  found", 18:55 UTC 12 Nov) while the local services kept running. Restarted at 00:40 Colombo on
  13 Nov under `caffeinate -i` (idle sleep prevented while the script runs; a closed lid still
  sleeps); checked from outside: only `POST /api/v1/zoom/webhooks` reaches the backend (unsigned
  → 401), everything else 404. The owner must update the Zoom subscription URL and validate it.
  The stop also showed the script left its web server running (it killed the wrapper subshell);
  the server is now started with `exec`, so stopping the script stops it.

## Checkpoint AUD-1 — 2026-11-13 — Activity log

- **Branch:** `feature/activity-log`. **PR / merge SHA:** see the merged pull request titled
  "feat(audit): activity log for owners and administrators" (PR #55, merge commit `a5a1245`;
  all CI checks green; CI backend ran all 327 tests).
- **Implemented:** `GET /api/v1/audit-events` (owner/admin, read-only): newest first, keyset paging
  on (time, id) so events sharing a timestamp page correctly, filters by area (staff, sign-in,
  students, zoom) or by one record (`targetId`), with the actor's email and a target label
  (account email, batch code, class title). `/staff/activity` describes each action in plain
  language ("Disabled …", "Imported 600 students", "Zoom links re-checked after … changed") with
  who did it and when; the staff record page links to that account's history. New "Activity log"
  item in the staff navigation.
- **Found and fixed:** the seventh navigation item made the rail wider than a phone screen, which
  stretched every staff page on phones (412 px screen, 449 px page) and made taps land in the wrong
  place. The phone layout's column can no longer grow past the screen, and the menu wraps inside it.
- **Tests:** backend `AuditEventHttpIntegrationTest` (4 tests: order and labels, area and record
  filters, paging across same-instant events without gaps or repeats, access and validation).
  Unit tests for the descriptions; Playwright `activity-log.spec.ts` (desktop and mobile, including
  a phone-fit check on the record and activity pages).
- **Local verification note:** the full backend suite could not finish locally: another project's
  ~20 containers took most of Docker's 4 GB and the test JVM was killed before running ("forked VM
  terminated"); the new audit test had passed on its own earlier. The CI job is the full run.
- **Also on 13 Nov:** an app restart closed the tunnel's terminal; the backend behind the still-running
  tunnel was restarted with the new `resume` mode (PR #54, `0582e8f`), keeping the validated Zoom URL.

## Checkpoint OPS-1 — 2026-11-13 — Backups proven restorable

- **Branch:** `feature/restore-drill`. **PR / merge SHA:** see the merged pull request titled
  "feat(ops): backup script, restore drill and a restore test in CI" (PR #56, merge commit
  `7c7c257`; all CI checks green; CI backend ran all 328 tests).
- **Implemented:** `scripts/db-backup.sh` (pg_dump custom format to gitignored `backups/`, private
  permissions, personal-data warning); `scripts/db-restore-drill.sh` (fresh dump → throwaway PostgreSQL 17
  → identical Flyway history, matching table counts, audit log still append-only; everything deleted
  afterwards); `BackupRestoreIntegrationTest` in CI (seeded data from every area, Flyway `validate` on the
  restored copy, every table's full contents equal, restored audit log refuses an update). `RESTORE.md`
  rewritten from "planned" to the tested procedure.
- **Drill run:** against the Zoom test database, 13 Nov — PASSED (15 migrations identical, 23 tables,
  62 rows, update of 6 audit events refused). The first run reported "latest V9" (text comparison of
  versions); fixed to the last applied migration.
- **Still open:** RPO/RTO/retention (OPEN-QUESTIONS P1) and hosting budget (P0) decide the production
  backup design; no cloud resources were created.

## Checkpoint AUD-2 — 2026-11-13 — Timetable changes audited; failed invitations listed

- **Branch:** `feature/timetable-audit`. **PR / merge SHA:** see the merged pull request titled
  "feat(audit): record timetable changes and list failed invitation emails" (PR #57, merge commit
  `50ff4cd`; all CI checks green).
- **Timetable audit:** attendance is judged against the timetable, so every change is now in the
  activity log: `CLASS_SCHEDULED` (time, zone, batch count, webinar set or not), `CLASS_UPDATED` (which
  fields changed; old and new time when it moved; batches added/removed), `CLASS_CANCELLED`,
  `CLASS_RESTORED`, one `CLASS_SERIES_CREATED` per series (classes, weekdays, first and last date) and
  one `CLASS_SERIES_CANCELLED` (from date, how many). A save that changes nothing is not recorded.
  The activity page gains a "Classes" filter and describes each in the class's own time zone.
- **Failed invitations:** `GET /api/v1/student-invitations/failures` lists students whose latest
  invitation email failed (last 14 days) and who have not activated; they leave the list when emailed
  again or activated. The students page shows an "Emails that failed" panel only when there are any.
- **Tests:** backend `TimetableAuditIntegrationTest` (2 tests) and failure-list checks in the
  invitation test; one Zoom test now counts only Zoom audit events. Frontend: description unit tests;
  Playwright for a bounced invitation and a class in the activity log. Four existing browser tests
  now look inside the student directory, since the students page can show a second list. Full local
  runs: backend 330 tests, 48 unit tests, 80 Playwright tests passed.
- **Tooling:** `src/lib/activity.ts` imports `./staff-forms.ts` with its extension so Node's test
  runner can load it; `allowImportingTsExtensions` is on (the app never emits TypeScript).

## Checkpoint SES-3 — 2026-11-13 — Change the rest of a weekly series

- **Branch:** `feature/series-edit`. **PR / merge SHA:** see the merged pull request titled
  "feat(sessions): change the rest of a weekly series at once".
- **Implemented:** `POST /api/v1/class-sessions/series/{id}/changes` (fromDate; any of title, start and
  end time, webinar ID or clearing it; dryRun). Applies to the series' scheduled classes from the date,
  each keeping its own date, zone and batches; cancelled ones are left alone. If a class would overlap a
  scheduled class of the same batches outside the change, nothing changes and the overlaps are listed.
  Zoom links are re-checked per class; one `CLASS_SERIES_UPDATED` audit event. Class page: "Change the
  rest of the series" (preview, then apply only after a clean preview of the same details); the activity
  log describes it.
- **Tests:** 4 new backend tests (preview then apply with earlier and cancelled classes untouched and the
  audit details; overlap refusal including with a title change; webinar change and clear; validation and
  access). Playwright series spec retimes the rest of a series and finds it in the activity log. Full
  local runs: backend 334, unit 49, Playwright 80 passed.
- **Closes** the SES-2 caveat "editing a whole series at once".

## Checkpoint 003A-2 — 2026-11-13 — First real test webinar received end to end

- **Branch:** `docs/003a-webinar-findings` (documentation only).
- **Run:** webinar 838 3442 8148 with the host and three test attendees, 11:28–11:32 Colombo, over
  the local tunnel. The quick tunnel had been dropped by Cloudflare at 10:59 (error 1033) and was
  replaced by restarting the tunnel container; the Zoom subscription was updated, validated, saved
  and re-read after a reload (an earlier validated URL had never been saved).
- **Result:** 12 events stored once each and processed on the first attempt (1 started, 5 joined,
  5 left, 1 ended) into one occurrence; the read-only API check reported 3 registrants and
  "5 join sessions by 4 people", matching the webhooks. Details, compared as hashes only, are in
  `docs/integration/ZOOM-TEST-WEBINAR.md` ("Findings").
- **Design inputs for 003D part 2 / 004A:** identify a person by `registrant_id` (then email);
  pair a join with its leave by `participant.user_id`, which is new on each rejoin; the host has
  `participant_user_id`, attendees do not. Payloads carry public/private IP addresses, which must
  be stripped before storage before any real student data (P0).
- **Afterwards:** tunnel stopped (public URL 530); test events kept in the `luma-zoom-test-data`
  volume; the Zoom event subscription should be deactivated until the next test.
- **Next:** participant-to-registrant matching and visit pairing (003D part 2), then attendance
  sessions (004A); dropping IP addresses from stored payloads.

## Checkpoint 003D-2 — 2026-11-13 — Zoom participants matched to students

- **Branch:** `feature/zoom-participant-matching`. **PR / merge SHA:** see the merged pull request titled
  "feat(zoom): match webinar participants to students".
- **Implemented:** V19 adds visit, registrant ID, email, display name and host flag to `zoom_occurrence_events` (filled for
  earlier events from their receipts; IP addresses never copied) and `zoom_identity_links` (an administrator's decision per
  Zoom email). `GET /api/v1/zoom/participants?occurrence=`, `GET /api/v1/zoom/participants/unmatched`,
  `GET|POST /api/v1/zoom/identity-links`. Matching: decision first, then the student's sign-in email; the host is never
  matched; decisions are audited and named in the activity log. Staff UI: "People in this occurrence" page and a
  "Zoom accounts to match" panel on the Zoom review page (match by student number, or "Not a student").
- **Real data check:** the migration's backfill, run in a rolled-back transaction on the 13 Nov test webinar's events,
  flagged the host and found 3 attendees with 4 visits, matching Zoom's report.
- **Tests:** `ZoomParticipantIntegrationTest` (grouping across rejoins and letter case, sign-in match, expected flag,
  host, visitor without email, no IP stored; decisions override and leave the review list, replacement and audit;
  validation, roles and academy isolation). Playwright: matching by sign-in email and by an administrator. Local: Zoom
  backend tests 57 passed; unit tests and 82 Playwright tests passed; the full local backend run was killed by Docker
  memory (exit 137) after 107 passing tests, so CI's backend job is the full-suite evidence.
- **Still open:** strip IP addresses from stored receipts before real students; removing a decision; attendance (004A).

## Checkpoint 004A — 2026-11-13 — Provisional attendance per class

- **Branch:** `feature/provisional-attendance`. **PR / merge SHA:** see the merged pull request titled
  "feat(attendance): provisional attendance from live Zoom events".
- **Implemented:** `GET /api/v1/class-sessions/{id}/attendance` (owners, admins, and assigned staff for their classes and
  students). For each expected student, and for matched students not expected (managers only): time connected within the
  class time with rejoins merged (existing `AttendanceDurationCalculator`), first join and seconds after the start, visits,
  and visits with no leave yet, counted to the webinar's end or to now while it runs; a visit whose join never arrived
  counts from the webinar's start. Also linked occurrences, whether the webinar ended, and how many Zoom participants are
  unmatched. Recalculated on every read; nothing stored. Class page: "Provisional attendance" section.
- **Policy:** facts only. No present/absent, because the attendance threshold is an open P0 question, and not being seen
  in live events is not proof of absence (ZOOM-RULES 4 and 9). Official reconciliation is 004B.
- **Tests:** `ProvisionalAttendanceIntegrationTest` (4: overlap and clipping, lateness, open visit to the webinar end and
  to the class end, unseen and not-expected students, host and unmatched counts; no linked occurrence; instructor scope,
  student and other-academy refusals). Playwright: the class page after matching and linking. Unit: duration format.
  Local full runs: backend 341, unit 50, Playwright 82.
- **Not built:** live push (SSE); a stored, finalised record (004B); corrections (004C); exports (005A).

## Checkpoint 004B-1 — 2026-11-13 — Zoom's participant report replaces live events

- **Branch:** `feature/zoom-report-reconciliation`. **PR / merge SHA:** see the merged pull request titled
  "feat(attendance): reconcile with Zoom's participant report".
- **Implemented:** V20 `zoom_report_fetches` and `zoom_report_participants` (no IP, device or location fields).
  `ZoomReportService` fetches the report for ended, linked occurrences (poller after 15 minutes; backoff 15 min doubling to
  8 h or Retry-After; 6 attempts), calls Zoom outside transactions, replaces earlier copies, and never discards a report on a
  later failure. `POST /api/v1/zoom/reports` for an administrator (audited `ZOOM_REPORT_FETCHED`; 409 before the end or
  without API credentials). Attendance uses report rows where present; status RECONCILED when every linked occurrence has
  one. Occurrences show `ended` and report status. UI: report state and "Fetch Zoom's report" on Zoom review; the class
  page says when figures come from Zoom's report.
- **Tests:** `ZoomReportIntegrationTest` (4, fake Zoom server: replacement of live data incl. a student missed live, guest
  and host and no-email rows; re-fetch and failure keeping the earlier report, audit; 429 Retry-After backoff; not ended,
  unknown, instructor). Playwright: fetch from Zoom review and reconciled wording on the class page.
- **Still open:** finalising present/absent needs the academy's attendance rule (P0); a golden dataset from a real class;
  corrections (004C).

## Checkpoint 005A — 2026-11-13 — Attendance CSV exports

- **Branch:** `feature/attendance-exports`. **PR / merge SHA:** see the merged pull request titled
  "feat(attendance): CSV exports for a class and a batch".
- **Implemented:** `GET /api/v1/class-sessions/{id}/attendance/export` and `GET /api/v1/batches/{id}/attendance/export?from&to`
  (inclusive class dates, at most 92 days; only that batch's students). Rows are the attendance view's facts for the same
  caller, with the data source (PROVISIONAL or RECONCILED) on each row and times in the class's zone. `text/csv`, attachment,
  `Cache-Control: no-store`, `nosniff`; cells a spreadsheet would run as a formula get a leading apostrophe. Every export is
  audited (`ATTENDANCE_EXPORTED`; shown under "Classes & attendance" in the activity log). Web: downloads go through route
  handlers (`/staff/sessions/{id}/attendance-export`, `/staff/batches/{id}/attendance-export`) so the access token stays on the
  server; "Download CSV" on the class page and a date-range export on the batch page.
- **Found while testing:** on a phone the batch export's date inputs and button ran off the right edge (x 475 px of 412);
  the row now wraps, and the browser test checks the link stays on screen.
- **Tests:** `AttendanceExportIntegrationTest` (4: exact rows incl. a formula-like name, headers, audit; batch range and
  limits; instructor scope incl. a shared class; cell quoting). Playwright: both downloads on desktop and phone and the
  activity entries. Unit: activity wording. Local: unit 51, Playwright 84.

## Checkpoint 004B-2 — 2026-11-13 — The academy's rule and final attendance

- **Branch:** `feature/attendance-finalisation`. **PR / merge SHA:** see the merged pull request titled
  "feat(attendance): the academy's rule and final attendance".
- **Implemented:** V21 `attendance_policies` (one per academy, versioned) and `attendance_records` (per expected student:
  computed and in-force outcome, facts, the rule used, who and when). `GET|PUT /api/v1/attendance-policy` (owners set it;
  staff with attendance access read it; audited). `POST /api/v1/class-sessions/{id}/attendance/finalisation` (owners and
  administrators): only with a rule, a finished scheduled class and Zoom's report for every linked occurrence; once per class.
  `AttendanceRule` decides PRESENT / LATE / ABSENT. The attendance view and CSV show the final outcome. UI: "Attendance" page
  (rule), "Finalise attendance" on the class page, outcome badges.
- **Policy:** LUMA does not choose the threshold; the owner records the academy's approved rule (OPEN-QUESTIONS P0 asks for
  it). Live events alone never finalise.
- **Tests:** `AttendanceRuleTest` with a 15-case golden dataset (`src/test/resources/attendance/golden-outcomes.csv`);
  `AttendanceFinalisationIntegrationTest` (rule roles, validation, versioning and audit; refused without a rule or the
  report, once per class, the rule kept after a change, CSV outcome; unfinished, cancelled, roles, other academy). Playwright:
  owner sets the rule, admin reads it, finalising a past class after fetching the report. Local: unit 52, Playwright 88.
  Docker Desktop on the Mac stopped responding during this slice, so backend tests ran in CI only.

## Checkpoint 004C — 2026-11-13 — Corrections, student history and appeals

- **Branch:** `feature/attendance-corrections`. **PR / merge SHA:** see the merged pull request titled
  "feat(attendance): corrections, student history and appeals".
- **Implemented:** V22 `attendance_appeals` (one open per record) and `attendance_corrections` (append-only by trigger).
  Corrections: `POST /api/v1/attendance-records/{id}/corrections` (owners, administrators, attendance officers for students of
  their batches; reason; version check; computed outcome kept; audited) and their history. History:
  `GET /api/v1/students/{id}/attendance` (staff) and `GET /api/v1/me/attendance` (student). Appeals:
  `POST /api/v1/me/attendance/{id}/appeals`, `GET /api/v1/attendance-appeals`, `POST /api/v1/attendance-appeals/{id}/resolution`
  (accepting corrects the record, linked to the appeal; rejecting needs a note the student sees). UI: "Correct" on final rows,
  attendance history on the student page, "My attendance" with appeals in the student portal, an appeals list on the
  Attendance page; activity log wording for every attendance action.
- **Tests:** `AttendanceCorrectionIntegrationTest` (3: history and append-only trigger, version and validation; officer scope;
  appeal lifecycle, one open, own record only, accept/reject, audit). Playwright: correction and history; student appeal
  accepted by an administrator. Local: unit 52.

## Checkpoint 006 — 2026-11-13 — Engagement with consent (reactions, pulse questions, rankings)

- **Branch:** `feature/engagement`. **PR / merge SHA:** see the merged pull request titled
  "feat(engagement): portal reactions, pulse questions and rankings with consent".
- **Implemented:** V23 `engagement_settings`, `engagement_consents`, `portal_reactions`, `pulse_questions`, `pulse_answers`.
  Owners switch engagement on with the consent wording (`/api/v1/engagement-settings`; new wording raises the consent
  version). Students opt in, and separately opt in to being named in rankings (`/api/v1/me/engagement-consent`). Reactions and
  pulse answers only from expected, consenting students while the class runs (±10 minutes); reactions one per 10 seconds and
  at most 120 per class; pulse questions 2–5 distinct options, 1–10 minutes, at most 20 per class, one answer each. Staff
  see counts per class (`/api/v1/class-sessions/{id}/engagement`); managers see top 10/100 per batch and range with the
  scoring method and sources (`/api/v1/batches/{id}/engagement-ranking`). UI: settings on the Attendance page, consent and a
  "Take part" page in the student portal, an Engagement panel on the class page, a ranking on the batch page.
- **Not used:** Zoom Q&A and poll reports (the academy's app has no Q&A/poll report scopes; adding them is an account change
  for the academy) and Zoom emoji (anonymous). Peer-visible leaderboards (OPEN-QUESTIONS P1) are not offered: staff only.
- **Policy:** off by default; switch on only after OPEN-QUESTIONS P0 (ages, consent, retention) is signed off.
- **Also:** `scripts/staff-ui-real-stack.sh` greps the invitation token with `-F -e`, since a random token starting with `-`
  made grep fail (seen once in CI on PR #65).
- **Tests:** `EngagementIntegrationTest` (3: off by default, owner-only settings, consent and new wording, rate limit,
  unknown kind; pulses open/validate/answer once/close; rankings cap, consent-only, naming opt-in, roles). Playwright: owner
  switches on, student consents and reacts, staff open and close a question, ranking. Local: unit 52, Playwright 92.

## Checkpoint PRIV-1 — 2026-11-13 — Zoom receipts stored without network and device details

- **Branch:** `feature/strip-zoom-ips`. **PR / merge SHA:** see the merged pull request titled
  "fix(privacy): store Zoom receipts without IP addresses or device details".
- **Change:** `ZoomWebhookReceipts` removes participant `public_ip`, `private_ip`, `ip_address`, `location`, `device`,
  `network_type`, `mac_addr` and `internal_ip_addresses` before storing a payload; the signature is still checked over the raw
  body and duplicates are still recognised by the raw body's SHA-256. V24 removes the same fields from receipts already stored.
  This was found in the first real test webinar (join/leave events carry public and private IP addresses).
- **Tests:** `ZoomParticipantIntegrationTest` asserts stored receipts contain no IP and keep everything else.

## Checkpoint 007A — 2026-11-13 — Infrastructure as code and operations runbooks (not applied)

- **Branch:** `feature/infra-terraform`. **PR / merge SHA:** see the merged pull request titled
  "feat(infra): Terraform for the AWS design, validated in CI, plus runbooks".
- **Implemented:** Terraform modules `network` (VPC, 2 AZs, public/private subnets, 1–2 NAT), `database` (RDS PostgreSQL 17,
  private, encrypted, `rds.force_ssl`, managed master secret, backups, deletion protection), `storage` (private bucket,
  KMS, versioning, TLS-only policy, export/backup lifecycle), `messaging` (SQS + DLQ), `compute` (ECS Fargate web + API,
  HTTPS ALB with only the Zoom webhook path to the API, Cloud Map for web→API, least-privilege task roles, secrets by ARN,
  circuit-breaker rollback, CPU autoscaling), `monitoring` (SNS + 9 alarms incl. log-based Zoom dead-letter and report
  failures); `environments/staging` composes them with empty secret containers. `terraform validate` passes (Terraform
  1.9.8, AWS provider ~> 5.80); new CI workflow `terraform` (fmt + validate, no credentials, never plans or applies).
  Runbooks: `DEPLOY.md` (first deploy, releases, rollback, feature switches), `INCIDENT-RESPONSE.md`, `COSTS.md` (cost
  drivers without invented prices).
- **Not done:** no AWS resources exist; applying needs the academy's budget approval and account (ADR-0006 gates).

## Checkpoint 007B — 2026-11-13 — Pilot readiness pack

- **Branch:** `feature/pilot-pack`. **PR / merge SHA:** see the merged pull request titled "docs(pilot): pilot plan, UAT,
  sign-off, guides and go-live evidence".
- **Added:** `docs/delivery/PILOT-PLAN.md` (decisions → staging UAT → one batch → all, with exit criteria and rollback
  switches), `SIGN-OFF.md` (the academy's decisions and phase sign-offs), `UAT-SCRIPT.md` (17 cases, each mapped to a user
  story and its automated tests), `STAFF-GUIDE.md`, `STUDENT-GUIDE.md`; `GO-LIVE-CHECKLIST.md` now shows status and evidence
  per gate.
- **Remaining for the academy:** P0 decisions, AWS account and budget, staging deployment and UAT, the one-batch pilot and
  sign-offs. No production data has been collected.

## Checkpoint 004A-2 — 2026-11-13 — Live updates on a running class's page

- **Branch:** `feature/live-refresh`. **PR / merge SHA:** see the merged pull request titled "feat(attendance): keep a running
  class's page up to date".
- **Implemented:** while a class runs (15 minutes before to 30 minutes after) and is not final, the class page re-renders from
  the server every 30 seconds when the tab is visible, with a Pause/Resume control, so provisional attendance and engagement
  counts stay current. This replaces the planned SSE channel: no long-lived connections for 600 users, nothing new to secure.
- **Tests:** Playwright `live-refresh.spec` (a class running now shows the notice; pause works) on desktop and phone.

## Checkpoint ZM-LOOKUP — 2026-11-15 — Check any webinar by join link or ID

- **Branch:** `feature/webinar-lookup`. **PR / merge SHA:** see the merged pull request titled "feat(zoom): check any webinar's
  attendance by its join link or ID".
- **Implemented:** `ZoomLookupService`: reads the webinar ID from a join link (`/j/`, `/w/`, `/s/`, `/wc/join/`, `/webinar/`,
  https only) or a typed ID (spaces/hyphens allowed); registration links are refused with guidance. Lists past runs from
  Zoom, fetches one run's participant report (only a run of that webinar; stored like other reports; audited), shows people
  by email with join sessions merged, matched to students like live participants, and links a run to a class (manual,
  audited) so the class counts it from Zoom's report and can be finalised. Staff page "Check a webinar" (from Zoom review).
- **Real check (15 Nov):** `scripts/zoom-api-check.sh "https://us06web.zoom.us/w/83834428148?tk=…"` resolved the link to the
  test webinar and produced the page's view of the 13 Nov run from Zoom's report: 5 join sessions → 4 people, all with email,
  1–3 minutes each (counts only; identities not printed).
- **Tests:** `ZoomLookupIntegrationTest` (3: link and ID parsing incl. refusals; a never-seen past run fetched, grouped,
  matched, linked and counted as RECONCILED in the class; unknown webinar/run, report-first, roles). Playwright
  `webinar-check.spec` on desktop and phone.
- **Limits:** Zoom meetings are not covered (webinars only); runs linked this way are not listed on the Zoom review page
  (they have no live events) but appear on their class.

## Checkpoint FIX-1 — 2026-11-15 — Zoom flow fixes from the gap audit

- **Branch:** `fix/zoom-flows`. Fixes audit items 6, 7, 9, 10 and 14.
- Runs known only from Zoom's report (checked by link or ID) are listed in Zoom review and on their class ("from Zoom's
  report"), with "people" linking to the right view; decisions work for them too.
- The host is recognised in report rows (participant id = the webinar's `host_id` from Zoom, or a host email known from
  live events) and never counted as an unmatched person.
- Registrants are stored at report time (`zoom_registrants`, V25); someone who joined from another Zoom account but
  registered with the email LUMA knows is matched (source REGISTRATION_EMAIL), in live and report views.
- After 6 failed report attempts the run says so ("GAVE_UP") instead of "will retry".
- An administrator can remove a Zoom-account decision (audited), restoring the automatic match.
- **Tests:** new cases in `ZoomReportIntegrationTest`, `ZoomParticipantIntegrationTest`; Zoom/attendance backend tests 88/88;
  Playwright 96, unit 52.

## Checkpoint FIX-2 — 2026-11-15 — Recalculate a final class

- **Branch:** `fix/refinalise`. Fixes audit item 8.
- `POST /api/v1/class-sessions/{id}/attendance/recalculation` (owners/admins, reason, audited `ATTENDANCE_RECALCULATED`):
  recomputes every record from Zoom's current report with the rule it was finalised with; corrected records keep their
  outcome (facts refreshed); students expected now without a record get one. UI: "Recalculate from Zoom's report" on a final
  class. Tests: `AttendanceFinalisationIntegrationTest` (recalculation keeps a correction, changes an uncorrected record,
  refusals); Playwright in `attendance-rule.spec`.

## Checkpoint FIX-3 — 2026-11-15 — Security and privacy fixes from the gap audit

- **Branch:** `fix/security`. Fixes audit items 18–22.
- **Two-step sign-in for every staff role** (instructors, attendance officers, analysts as well as owners/admins): a staff
  role on a session without MFA is refused. Tests and the real-stack flow updated (the instructor enrols an authenticator).
- **Change password** (`POST /api/v1/auth/password`, signed in, current password re-checked with lockout, policy applied,
  every session ended, audited `PASSWORD_CHANGED`; limited like sign-in). Page `/account/password` for staff and students.
- **Per-account limits** on heavy actions (exports, Zoom lookups/report fetches, rankings): 60 per 10 minutes by default,
  429 with Retry-After (`luma.rate-limit.heavy-staff`).
- **Erasure on request** (`POST /api/v1/students/{id}/erasure`, owners, number typed again, audited without personal data):
  student anonymised, sign-in disabled and email replaced, MFA/invitations/resets removed, Zoom names/emails/registrations
  for all the student's known emails cleared, reactions/answers/consent deleted, appeal texts replaced. Attendance figures
  stay. The append-only audit log keeps Zoom emails only for identity decisions (documented).
- **Retention** nightly (02:30 Colombo): processed raw Zoom payloads lose names/emails after 180 days (`luma.retention.*`);
  failed events keep them for replay; engagement deletion configurable (0 keeps).
- **Academy decisions recorded (15 Nov):** student ages are not an issue; data is collected only for attendance; guardians agree.
- **Tests:** `PrivacyIntegrationTest` (3), `AccountRateLimitIntegrationTest`, updated auth/MFA/staff tests (all pass locally);
  Playwright `account-privacy.spec` and instructor MFA in 7 specs; unit 53, Playwright 100.

## Checkpoint FIX-4 — 2026-11-15 — Notices, take-part timing, live student page, course rankings

- **Branch:** `fix/student-flows`. Fixes audit items 11, 12, 13 and 23.
- **Email notices** (`AccountNotifier`, after commit on the account-mail queue, only when mail is on and the account active):
  the student when a record is corrected or an appeal is accepted (new and old outcome, reason) or rejected (note);
  owners/administrators when a student appeals. No personal data in logs.
- **"Take part"** shows only from 10 minutes before to 10 minutes after a class; the student's class page refreshes every 20 s
  until 10 minutes after the end (class times now in the student engagement response).
- **Rankings:** over the whole course (`GET /api/v1/courses/{id}/engagement-ranking`) as well as a batch, and as CSV
  (`…/engagement-ranking/export`, audited `ENGAGEMENT_RANKING_EXPORTED`, formula-safe, unnamed rows stay unnamed).
- **Tests:** notice assertions in `AttendanceCorrectionIntegrationTest`; course ranking and CSV in `EngagementIntegrationTest`;
  unit tests for the take-part window and wording; Playwright engagement/portal specs. Unit 55, Playwright 100.

## Checkpoint FIX-5 — 2026-11-17 — Testing gaps closed

- **Branch:** `fix/test-gaps`. Fixes audit items 24–28.
- **Real stack covers attendance** (`scripts/staff-ui-real-stack.sh` seeds a past run with live events and Zoom's report):
  scheduling the class links the run, participants match by sign-in email, the owner records the rule, the class is
  finalised from the report, a correction reaches the student **by real SMTP**, the CSV and the student portal agree, and the
  activity log shows it. Passed locally end to end on the real backend, database, web app and mail server.
- **Mock isolation:** each Playwright project (desktop, phone) runs its own mock backend and web server, so no test depends on
  another project's data.
- **Accessibility:** `accessibility.spec` runs axe (WCAG 2.1 A/AA) on public, staff and student pages; serious or critical
  issues fail. It found low-contrast muted text (4.42:1 on the page background); `--pca-muted` is now #4a6684 (5.57:1).
- Local: Playwright 106 (incl. accessibility on both projects), real-stack 1/1.

## Checkpoint FIX-6 — 2026-11-17 — Operations gaps closed (infrastructure code, not applied)

- **Branch:** `fix/ops`. Fixes audit items 31–35.
- **Unused SQS queue removed:** Zoom events are queued in PostgreSQL; the module, IAM grant and DLQ alarm are gone.
- **WAF** in front of the load balancer (AWS common + known-bad-inputs rule groups, per-address rate limit; body-size rule
  counts only, for CSV uploads). **VPC endpoints:** S3 gateway always (free); ECR/Logs/Secrets Manager optional.
- **AWS Backup:** daily snapshot at 02:00 Colombo, own vault, kept 35 days, beside RDS point-in-time recovery.
- **Alarms:** four more log-phrase alarms (webhook signature, webhook storage, retention, email). `AlarmLogPhrasesTest`
  fails the build if the API stops writing any phrase an alarm counts.
- **Images workflow:** builds both Dockerfiles on every relevant PR (no push, no deploy) and checks they run as non-root.
- **Branch protection:** available but off; documented in CONTRIBUTING.md for the owner to enable.
- Local: `terraform fmt`/`validate` pass (1.9.8), actionlint passes.

## Checkpoint FIX-7 — 2026-11-17 — Documentation brought up to date

- **Branch:** `fix/docs`. Fixes audit items 15–17, 29–30, 36–40 (documentation only).
- README, START-HERE, architecture overview, ZOOM-RULES status, staff and student guides now describe what is built
  (MFA for all staff, password change, recalculation, notices, registration matching, host rows, erasure, retention,
  course rankings, WAF/backups) and drop "planned SQS/SSE".
- New `docs/delivery/KNOWN-LIMITS.md`: webinars only, one academy per deployment, WN_ links, what needs a deployment or
  more real webinars (load at 600, multi-occurrence recurring webinars, AWS), the Zoom subscription switch, branch
  protection, the Notion page, PR #71.
- LICENSE_DECISION_REQUIRED.md now says private. OPEN-QUESTIONS records the owner's answers of 17 Nov.

## Checkpoint FIX-8 — 2026-11-17 — UAT, simulated pilot, re-audit fixes

- **Branch:** `fix/uat-reaudit`.
- **UAT (developer run):** 17/17 passed on the real local stack and the real Zoom account (`docs/delivery/UAT-RESULTS.md`).
  New real-stack test 2 covers appeals, engagement on/consent/reactions (rate limit), a 3-minute question, the batch
  ranking and phone layouts. Real Zoom API check and a restore drill (25 migrations, 37 tables) passed the same day.
- **Simulated pilot:** `PilotSimulationIntegrationTest`, one batch for two weeks against a hand-written register:
  189/189 records, 0 differences (`docs/delivery/PILOT-SIMULATION.md`).
- **Re-audit fixes:**
  - Zoom review now lists and accepts decisions for emails seen only in Zoom's report (found by the pilot rehearsal), and
    leaves out people matched by registration email.
  - The OpenAPI contract lacked five endpoints from the fix series; `scripts/check-openapi.py` and the `contract` workflow
    now fail on any difference (100 endpoints).
  - Diagrams, schema plan, webhook runbook, JWT-AUTH and two package docs described old states (SQS, "planned",
    owner/admin-only MFA, no password recovery); rewritten to what is built.
  - KNOWN-LIMITS: attendees without an email in Zoom's data cannot be matched (keep registration required).

## Checkpoint FIX-9 — 2026-11-17 — Web hardening (re-audit)

- **Branch:** `fix/web-hardening`.
- The web app sent no security headers. Every response now carries a same-origin Content-Security-Policy (no frames,
  no objects, no eval in production), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: same-origin`, a
  Permissions-Policy, COOP and HSTS (`next.config.ts`).
- Branded "Page not found" and "Something went wrong" pages replace Next's defaults (`not-found.tsx`, `error.tsx`).
- `security-headers.spec`: headers on public, staff, student and unknown pages; no CSP violation while signing in and
  using staff and student pages. Local: Playwright 120 passed.

## Checkpoint FIX-10 — 2026-11-17 — Attendance summaries and the analyst role (re-audit)

- **Branch:** `feat/attendance-summary`.
- Re-audit found that the PRD's "monthly attendance reports" existed only as raw CSV rows, and that the Analyst role could
  be granted but reached nothing.
- **Batch summary:** each student's final present/late/absent/excused and rate over a period, lowest rate first, on the
  batch page (managers and assigned staff). **Academy report:** totals per batch without names on the Attendance page
  (owners, administrators, analysts). Rate = (present + late) / (present + late + absent); excused left out; a class's
  date is its local date; cancelled classes are ignored; ended-but-not-final classes are counted separately.
- Tests: `AttendanceSummaryIntegrationTest` (counts, order, time zones, two batches, every role, other academy),
  `attendance-summary.spec` (both projects). Contract: 102 endpoints.

## Checkpoint FEAT-1 — 2026-11-17 — Less manual work: import from Zoom, finalise queue, automatic finalisation

- **Branch:** `feat/staff-automation`.
- **Import classes from Zoom:** `GET /api/v1/zoom/upcoming-webinars` lists the academy's upcoming webinars (Zoom
  `GET /users/{user}/webinars?type=upcoming`, every run of recurring ones, next 120 days), marking runs that already have a
  class (same webinar, start within 30 minutes). `POST /api/v1/zoom/webinar-imports` re-reads the webinar from Zoom and
  creates one ordinary class per chosen run for the chosen batches, all or nothing, never twice (audited
  ZOOM_WEBINAR_IMPORTED). Hosts: `luma.zoom.import.hosts` (default `me`, accepted by Zoom for this server-to-server app).
  Needs the scope `webinar:read:list_webinars:admin` (Zoom's own error names it; the real account does not have it yet).
- **Finalise queue:** `GET /api/v1/attendance/pending` (ended, not final, last 60 days; READY / WAITING_FOR_REPORT /
  NO_ZOOM_RUN) and `POST /api/v1/attendance/finalisations` (every ready class), on the Attendance page.
- **Automatic finalisation:** owner's switch on the rule (V26 `auto_finalise`, `auto_finalise_by`). A job every 5 minutes
  finalises ready classes in that owner's name while they are still an active owner; audited `automatic=true`; alarm on
  "Automatic finalisation failed".
- Tests: `ZoomImportIntegrationTest`, `AttendanceQueueIntegrationTest`, `staff-automation.spec` (both projects). Real Zoom:
  `zoom-api-check.sh` now also tries the listing; it reports the missing scope.

## Checkpoint FEAT-2 — 2026-11-17 — Staff overview: today and what needs attention

- **Branch:** `feat/staff-overview`. The overview still said attendance was "coming next" (stale since 004B).
- Now: today's classes (live now / ended / upcoming / no webinar ID) and, for owners and administrators, counts that link
  to where each is handled: classes ready to finalise, ended classes waiting for Zoom, appeals, Zoom accounts to match,
  failed Zoom events. Instructors and attendance officers see their batches; analysts their report.
- Tests: `staff-overview.spec` (both projects); the import page joins `accessibility.spec`.

## Checkpoint FEAT-3 — 2026-11-17 — Import from Zoom proven on the real account; built for scale

- **Branch:** `fix/import-scale`.
- Added the scope `webinar:read:list_webinars:admin` to the academy's Zoom app (Marketplace → Scopes → Add scopes →
  Continue). Zoom now grants it; `zoom-api-check.sh` lists **93 upcoming webinars** (all recurring) in 735 ms.
- Real data showed the first design (reading every webinar's details on each page load) would cost about 93 Zoom calls
  (~50 s). Now the list comes from the one listing call (topic, next date, classes already in LUMA; search by topic or ID),
  and a webinar's dates are read only when it is opened (`GET /api/v1/zoom/upcoming-webinars/{id}`; first real webinar: 15
  future dates in 543 ms).
- Tests: `ZoomImportIntegrationTest` (list order, counts, search, per-webinar dates), `staff-automation.spec`.

## Checkpoint CONFIG-1 — 2026-11-17 — Managed PostgreSQL & Supabase support

- **Branch:** `config/supabase-managed-db`.
- **Application config:** Added `spring.config.import` for optional `.env[.properties]` files (root and backend folders) to load local settings without manual shell export. Configured `spring.flyway.baseline-on-migrate: true` and `baseline-version: 0` so Flyway safely initializes and migrates managed PostgreSQL instances (e.g. Supabase, AWS RDS) where cloud routines or extensions exist in schema `public`.
- **Validation:** Successfully applied all 26 migrations against Supabase pooler, bootstrapped initial academy/owner, and verified system readiness.

## Checkpoint SEC-1 — 2026-11-17 — Production-grade signing and MFA key management

- **Branch:** `feat/production-auth-keys`.
- **Resilient PEM decoding:** Enhanced `SigningKeys.der()` to normalize escaped newlines (`\n`), allowing single-line escaped PEM blocks in environment variables and secret stores without breaking Base64 decoding.
- **Key generation tooling:** Added `scripts/generate-auth-keys.sh` to generate 2048-bit PKCS#8 RSA keypairs and 32-byte AES-256 Base64 keys with mode 600 permissions and optional `.env` injection.
- **Production enforcement:** Configured persistent keys with `LUMA_AUTH_EPHEMERAL_SIGNING_KEY=false`, verifying that ephemeral warning logs are eliminated and the application boots strictly with authenticated persistent keys.
- **Tests:** `SigningKeysTest` (including `supportsEscapedNewlinesInPemString`).

## Checkpoint PERF-1 — 2026-11-17 — Auth caching, pool tuning, and RSC deduplication

- **PR / Branch:** PR #93 (`perf/auth-caching-and-pool-tuning`).
- **In-memory token auth cache:** Added 15-second TTL in-memory cache to `AccountJwtAuthenticationConverter` to prevent redundant SQL queries (`accounts.findById`, `sessions.mfaStateIfActive`) on every incoming request when rendering multi-request RSC pages across physical cross-region latency.
- **Hikari pool warming:** Configured `minimum-idle: 5` and tuned `max-lifetime: 60000`, `idle-timeout: 30000`, and `keepalive-time: 30000` to prevent Supabase pooler disconnections.
- **Next.js RSC cache deduplication:** Wrapped `readIdentity` with `React.cache()` in `frontend/src/lib/auth-server.ts`.

## Checkpoint FEAT-4 — 2026-11-17 — Zoom configuration & cross-tab session synchronization

- **PR / Branch:** PR #94 (`feat/zoom-config-and-cross-tab-session-sync`).
- **Zoom API & Webhooks configuration:** Updated `backend/src/main/resources/application.yml` to support fallbacks for `ZOOM_ACCOUNT_ID`, `ZOOM_CLIENT_ID`, and `ZOOM_CLIENT_SECRET`. Loaded credentials and webhook tokens into `.env`. Verified with real Zoom API: listed 93 upcoming webinars in 721 ms, resolving "The Zoom API is not configured for this academy".
- **Cross-tab session synchronization:**
  - Added `frontend/src/components/SessionSync.tsx` coordinating session events across all open browser tabs via `BroadcastChannel` and `storage` events.
  - Automatically redirects all open tabs sitting on guest/sign-in pages (`/sign-in`, `/sign-in/mfa`, `/activate`, `/`, etc.) to the overview page (`/staff` or `/student`) upon sign-in in any tab.
  - Automatically handles sign-out propagation across tabs to `/sign-in?signedOut=1`.
  - Added `GET /api/session/status` for instantaneous re-validation when background tabs regain visibility/focus.
  - Added `isGuestPath` in `frontend/src/lib/session-policy.ts` with unit test coverage.
- **Tests:** `ZoomApiClientTest` (8 tests), `ZoomWebhookSignatureTest` (6 tests), `ZoomProcessingPropertiesTest` (3 tests), live `ZoomApiCheckCommand`, and frontend unit tests (56 tests).

## Checkpoint DEPLOY-1 — 2026-11-17 — Split Cloud Deployment (Oracle Cloud VM Backend + Vercel Frontend)

- **Architecture:** Modular monolith backend deployed to Oracle Cloud Infrastructure (OCI); frontend Next.js BFF deployed to Vercel.
- **Oracle Cloud VM Backend:**
  - Shape: `VM.Standard.E2.1.Micro` (Ubuntu 20.04 LTS, x86_64, 1 GB RAM, 3 GB configured swapfile).
  - Runtime: Eclipse Temurin 21 JRE (`21.0.13`), Spring Boot 4.1.2 managed via systemd (`luma-backend.service`) with G1GC memory tuning (`-Xms256m -Xmx512m`).
  - Reverse Proxy: Caddy v2.8.4 terminating TLS with auto-renewing Let's Encrypt certificates on `https://129.225.114.202.sslip.io`.
  - Database: Connected to Supabase managed PostgreSQL pooler; all 27 Flyway migrations verified.
  - Zoom Webhooks: Configured `https://129.225.114.202.sslip.io/api/v1/zoom/webhooks` with HMAC SHA-256 signature validation. Live events (`webinar.participant_joined`) confirmed receiving and storing.
- **Vercel Frontend:**
  - Deployed at `https://luma-pca.vercel.app/`.
  - Next.js 16.4.6 App Router with server-side BFF communicating over HTTPS with Oracle backend (`API_INTERNAL_URL=https://129.225.114.202.sslip.io`).
  - Production security headers, `__Host-luma-access` HttpOnly secure cookies, and cross-tab session synchronization live.

## Checkpoint CI-1 — 2026-11-17 — Automated CI/CD Pipelines for Backend and Frontend

- **PR / Merged commit:** PR #98 (`ci: add automated backend CD workflow for Oracle Cloud VM`).
- **Backend CD (`.github/workflows/deploy-backend.yml`):**
  - Triggers on push to `main` (changes to `backend/**`) or manual `workflow_dispatch`.
  - Uses GitHub Actions secrets (`OCI_HOST`, `OCI_USERNAME`, `OCI_SSH_KEY`).
  - Packages production Spring Boot JAR with Temurin JDK 21.
  - Automatically transfers the artifact over SSH to `/opt/luma/luma-backend.jar` on the Oracle Cloud VM.
  - Restarts the `luma-backend` systemd service.
  - Verifies public HTTPS health check on `https://129.225.114.202.sslip.io/actuator/health` reporting `"status":"UP"`.
  - First automated deployment completed successfully in 2m 26s (`run #44071529685`).
- **Frontend CI/CD:**
  - **CI:** `.github/workflows/frontend-ci.yml` executes linting, typecheck, unit tests, production build, and Playwright E2E browser tests on every PR and push.
  - **CD:** Native Vercel GitHub integration automatically deploys preview URLs on PRs and updates `https://luma-pca.vercel.app/` on merge to `main`.

## Checkpoint TEST-1 — 2026-11-18 — Playwright staff-zoom E2E Decision Collapse Race Condition Fix

- **Problem:**
  - `Frontend checks / e2e` failed on push to `main` (run `44079777096`) in `tests/e2e/staff-zoom.spec.ts:103:3 › Zoom review › Zoom participants are matched to students by sign-in email, or by an administrator with a reason`.
  - Root cause analysis revealed two factors:
    1. **Primary Timeout (Attempt 1):** In `tests/e2e/staff-zoom.spec.ts`, navigating to `/staff/zoom` was followed by `if (await occurrence.getByText("Change decision").count()) await occurrence.getByText("Change decision").click();`. In Playwright, `locator.count()` does not wait for elements. Immediately after navigation, `count()` evaluated to 0, skipping the click. As a result, the `<details className="decision">` remained collapsed, hiding `<select id="occ-3-class">`. Subsequent `selectOption` timed out waiting for the hidden element to be visible (exceeded 30000ms).
    2. **Secondary Failure on Retry:** Because the mock backend state persisted across retries, attempt 1 had already added students `ZM-D-1` and `ZM-M-1`. When Playwright retried, the test encountered conflict response `"A student with this student number already exists"`.
- **Fix:**
  - In `frontend/tests/e2e/staff-zoom.spec.ts`:
    - Ensured `await expect(occurrence).toBeVisible()` waits for the occurrence row to be rendered in the DOM.
    - Verified visibility of the class select: `if (!await select.isVisible()) { await occurrence.getByText("Change decision").click(); await expect(select).toBeVisible(); }`.
    - Directly called `selectOption` on the confirmed visible `select` locator.
- **Verification:**
  - Executed `npm --prefix frontend run test:e2e -- tests/e2e/staff-zoom.spec.ts`: all 8 tests passed in 29.4s (both chromium and mobile).
  - Executed `npm --prefix frontend run test`: 56 unit tests passed.
  - Executed `npm --prefix frontend run build`: Next.js production build succeeded.









## Checkpoint PERF-4 — 2026-11-18 — Instant feedback, one-trip sign-in check, fewer queries, Gmail-ready email

- **Branch:** `perf/instant-feedback-cache-email`. Plan: `docs/delivery/speed-and-email-plan.html`.
- **Instant feedback (Track A):** the sign-in check moves to `app/staff/layout.tsx` and `app/student/layout.tsx` (cookie
  identity, no backend call), so loading screens are back on every page while signed-out visitors still get a real 307;
  `src/proxy.ts` gives malformed record IDs a real 404 before rendering. One address-aware loading screen per portal,
  a pending dot on the clicked menu item (`useLinkStatus`), and a 30-second browser cache (`staleTimes`). In Next.js 16 a
  dynamic page without a loading screen is never prefetched, so this also brings prefetching back.
- **Sign-in check in one round trip (B1):** account, roles and session read with one SQL statement on every request (was 3);
  nothing cached, so sign-out, disabling and role changes still apply to the very next request.
- **Fewer queries (C2):** student views, sign-in-email matching and decided-student lookups are single joined queries.
  Round trips per request (local probe, pilot data): class attendance 22 → 16, roster 8 → 6, class students 8 → 6, Zoom
  accounts to match 9 → 5, plus the sign-in check 3 → 1 on every call.
- **Idle backend (D):** a stored Zoom webhook wakes the receipt worker at once (2-second check, no query when idle); with
  nothing arriving it looks for due retries once a minute instead of every 15 s.
- **Email (E1):** STARTTLS and 10-second SMTP timeouts in `application.yml`; SMTP login switches on whenever a username is set
  (`MailConfig`, `MailConfigTest`). Gmail steps in `runbooks/DEPLOY.md` and `.env.example`.

## Checkpoint PERF-5 — 2026-11-18 — Independent lookups in parallel, batch list cache, slow-call log

- **Branch:** `perf/parallel-queries-caching`.
- **Parallel lookups:** `shared/concurrent/Parallel` runs a request's independent reads at the same time on virtual threads.
  Class attendance reads Zoom activity and final records while it checks access and loads the class; engagement reads
  settings, reactions (one query instead of two) and questions while it checks the class. Nothing read in parallel is
  returned unless the access check passes. The class is no longer read twice for its expected students.
- **Batch list cache:** managers' batch lists cached per academy for 60 s, cleared at once and again after commit by any
  batch change; instructors' lists (assignment-dependent) are never cached. Pool cap 8 → 12 (`DB_POOL_MAX`).
- **Measured** with 100 ms added to every statement (≈ the Tokyo distance), main → branch: class attendance 1,699 → 1,048 ms,
  engagement 949 → 529 ms, batches 320 → 215 ms. The class page waits for its slowest call: ~1.7 s → ~1.05 s.
- **Slow-call log:** the web app logs backend calls over `LUMA_SLOW_API_MS` (800 ms) as
  `[luma] slow api GET /api/v1/class-sessions/:id/attendance 200 1235 ms` (IDs masked, no query string) to Vercel's logs.
- Local: backend full suite, Playwright 128, unit tests 58.

## Checkpoint ACAD-1 — 2026-11-18 — Academy classes linked to batches, rosters synced

- **Branch / PR:** `feat/academy-class-sync`, draft PR #107 (not merged: merging deploys).
- **Catalog:** reads the Academy's real `public.payment` (configurable), `type = 'class'` (case-insensitive), not
  deleted, non-blank class and ID. Counts distinct upper-cased `pcaid`, never payment rows. Optional student table
  (`pcaid`, `name`, `mail` only); details used only when exactly one live row matches. Table names checked against
  `schema.table`; 20 s query timeout; driver errors never reach the response.
- **Adapter, not a rebuild:** each Academy class links to one LUMA batch (`academy_class_links`, V27). Sync creates
  missing students by Academy ID, enrols them, never duplicates, never withdraws (leavers are listed), keeps staff
  withdrawals, and uses the Academy email as sign-in email only when unique (Zoom matches by email; others get an
  undeliverable placeholder and the existing identity decisions). Audit: counts and class label only.
- **API:** `GET /api/v1/academy/classes`, `PUT .../link`, `POST .../sync` — Owner/Admin only.
- **Tests:** `AcademyPaymentsCatalogIntegrationTest` (6, through a column-limited read-only role: cannot read amounts,
  NIC, the attendance table, or write), `AcademyClassSyncHttpIntegrationTest` (3).
- **Blocker:** live connection unverified — Supabase's pooler answers `user not found` for `luma_academy_readonly`
  (the role script likely rolled back). Credentials are in the local ignored `.env` only.
- **Open Academy decisions:** whether expired payments still count (baseline: historical); labels like `Admission`.

## Checkpoint ACAD-2 — 2026-11-18 — Academy-requested student ID sign-in (off by default)

- **Same branch / PR #107.** `POST /api/v1/auth/student-id-login` `{studentId, password}`: password must equal the ID
  (case-insensitive). Opens a normal student session only for an account whose sole role is STUDENT; pending accounts
  are activated with a random unknown password. Same 401 for every refusal; shares the per-client sign-in limit.
  404 unless `LUMA_STUDENT_ID_LOGIN_ENABLED=true` (API and web). Risk write-up: `docs/security/STUDENT-ID-LOGIN.md`.
- **Web:** sign-in page shows a "Students" form above the staff form when switched on (hidden for `?portal=staff`);
  BFF action `student-login` sets the same HttpOnly cookies as email sign-in.
- **Tests:** `StudentIdLoginHttpIntegrationTest` (6: own data only, staff routes 403, logout, refusals identical,
  staff-role account refused, other academy refused, rate limit 429), `StudentIdLoginDisabledHttpIntegrationTest` (1),
  Playwright `student-id-login.spec.ts` (3 × 2 projects). Local: Playwright 134 passed, unit tests pass, lint and build.
- **Next:** staff "Academy classes" page (list, link, sync); class → webinar mapping view with live per-student status.

## Checkpoint ACAD-3 — 2026-11-18 — Staff "Academy classes" page and frontend follow-ups

- **Same branch / PR #108.**
- **`/staff/academy` (owners/admins; nav item 02):** every Academy class with its distinct-student count and status.
  - "Set up in LUMA" (one click): creates the `ACADEMY` course the first time, a batch named after the class (code derived and
    kept unique), links it and syncs it.
  - "Or use an existing batch": links a batch that already has history, then syncs.
  - "Sync students": shows the result and lists what needs a decision (no name, no usable email, leavers, staff withdrawals,
    deactivated, unusable IDs).
  - Links to the batch roster and the batch's classes and webinars.
  - 503 from the API shows a "not connected yet" notice, and the old workflows keep working.
  - One form action per row, so the outcome stays visible after the page refreshes.
- **Follow-ups:**
  - Students and Courses pages point to Academy classes for students and batches that come from the Academy.
  - Invite panels say students can sign in with their ID (when switched on).
  - The student portal shows "Signed in as <name> · student ID <id>".
  - Backend: bulk invitations never email the `@students.academy.invalid` placeholders (`StudentInvitationHttpIntegrationTest`).
  - Fixed the CI failure: the sync test now drops its synthetic schema and role, which the backup/restore test was tripping on.
- **Tests:** unit 62 (new `academy.test.mjs`), Playwright `academy-classes.spec.ts`, full suite below, lint, build.

## Checkpoint ATT-DASH — 2026-11-18 — Attendance dashboards (staff and student), redesigned around the Academy's request

- **Same branch / PR #108.** The Academy found the existing screens hard to use; they asked for one place showing student
  ID, name, time in the webinar and attendance, filterable by course, with leave/rejoin detail per student.
- **Backend:**
  - `AttendanceInsightsService` with `GET /api/v1/attendance/dashboard` (course or batch, 1-92 days), which returns totals,
    classes, students and per-class cells.
  - `GET /api/v1/students/{id}/attendance-overview` and `GET /api/v1/me/attendance-overview` return courses and batches,
    totals per batch, and every class with its connection timeline.
  - Same facts as the class page: Zoom's report when fetched, else live events. Rejoins are merged and clipped to the class;
    once finalised, the final record's time and outcome are used.
  - At most 80 classes, computed 6 at a time.
- **Web:**
  - New **Dashboard** (nav 02): course and batch filters, quick periods and dates, and headline tiles (classes, students,
    average attendance against the rule, below the rule, connected now).
  - Three views: **Students** (search, show below-rule or never-joined, sort, a bar per student, opens the student page),
    **Classes**, and **Grid** (students × classes heat map).
  - **Student page** (`/staff/students/{id}/attendance`): tiles, courses and batches with each one's attendance, and class
    cards that expand into a timeline bar and the list of joins, leaves and "away 10 min, then rejoined".
  - **My attendance** for students (`/student/attendance`) uses the same layout. On phones the tables become cards.
  - "Overview" is renamed "Today", and it links to the dashboard.
- **Tests:**
  - `AttendanceInsightsIntegrationTest` (4): the Academy's 25 + 25 + 20 = 70-minute example with an overlapping duplicate,
    58%, 3 rejoins, filters, the student's own view, 403/404 isolation and the 92-day limit.
  - Unit tests: `insights.test.mjs`.
  - Playwright `attendance-dashboard.spec.ts` (4 × 2 projects). Full suite 144 passed, 2 skipped by design.

## Checkpoint PROD-ENV — 2026-11-18 — Synchronized Production Environment Configurations

- **Context:** Updated environment configuration in production to include read-only Academy payment database connection properties.
- **Backend Environment on Oracle Cloud VM (`/opt/luma/.env`):**
  - Updated `/opt/luma/.env` with the newly defined parameters:
    - `LUMA_ACADEMY_PAYMENTS_ENABLED`
    - `LUMA_ACADEMY_PAYMENTS_JDBC_URL`
    - `LUMA_ACADEMY_PAYMENTS_USERNAME`
    - `LUMA_ACADEMY_PAYMENTS_PASSWORD`
    - `LUMA_ACADEMY_STUDENT_TABLE`
  - Preserved `LUMA_AUTH_ISSUER=https://129.225.114.202.sslip.io` to ensure asymmetric JWT issuance parity.
  - Set file permissions to `0600` owned by `ubuntu:ubuntu` and created `/opt/luma/.env.bak`.
  - Restarted `luma-backend` systemd service: verified logs confirmed `academy-payments-readonly` Hikari connection pool initialized and started cleanly.
  - Verified public actuator health endpoint: `https://129.225.114.202.sslip.io/actuator/health` reporting `"status":"UP"`.
- **Frontend Verification:**
  - Verified `https://luma-pca.vercel.app/` responding HTTP 200 with CSP and security headers.
  - Confirmed `/staff` 307 unauthenticated redirects functional.


## Checkpoint UI-2 — 2026-11-18 — Frontend redesign after a review of every screen

- **Branch:** `feat/frontend-redesign`.
- **Review findings:**
  - The staff menu had 10 flat numbered links and scrolled away on long pages.
  - Titles were oversized and pushed content down.
  - The student portal had no "next class", no Zoom link, and showed a running class as "Scheduled".
  - The home page was almost empty, and the Today page sparse.
  - On phones the menu was a wall of links, and the headline tiles stacked one per row.
- **Changes:**
  - **Staff menu:** grouped (Overview · Teaching · People · Setup), with icons and no numbers; it stays in view while
    scrolling. On phones it becomes one horizontally scrolling strip.
  - **Page header:** compact, with an actions slot.
  - **Student portal:** its own menu (My classes, My attendance, password, sign out). The home page opens on a **Next class /
    Live now** card with **Join on Zoom**, then a last-30-days attendance snapshot, then classes and batches side by side.
    Running classes are marked "Live now"; past ones "Ended".
  - **Today (staff):** the date, a 7-day snapshot (classes, students, average attendance, connected now), live classes with a
    link to their live attendance, quick actions, and Needs attention.
  - **Home:** "I'm a student" and "I'm academy staff" cards.
  - **Sign-in:** student and staff forms side by side when student-ID sign-in is on.
- **Backend:** `StudentClassView.joinUrl` (`https://zoom.us/j/<webinar>`) for scheduled classes only; the raw webinar ID is
  still not sent. The Academy asked for joining links. `MyClassSessionHttpIntegrationTest` updated.
- **Tests:**
  - Playwright: 148 passed, 2 skipped by design. New: student next-class/join and home-page tests.
  - Axe now also checks `/`, the dashboard views, Academy classes, the student attendance pages and My attendance.
  - Unit tests and lint pass.

## Checkpoint SESSION-2 — 2026-11-18 — No more automatic sign-out; sign out still immediate

- **Branch:** `fix/session-stays-signed-in`.
- **Cause:** the 10-minute access cookie expired and nothing renewed it, so pages sent people to sign-in. Parallel
  renewals were also treated as token theft, which revoked the whole session.
- **Fix:**
  - The proxy silently renews on every page (one shared renewal per token).
  - The API gives a 30-second grace to a just-used refresh token, issuing a new token in the same session.
  - Migration V28 drops the one-current-token-per-session index.
  - Details in `docs/security/JWT-AUTH.md`.
- **Tests:**
  - Identity backend suite (153), including:
    - parallel renewals all succeed and keep the session;
    - a replay after the grace period revokes;
    - signing out kills an unexpired access token at once.
  - `silent-refresh.test.mjs`.
  - Playwright `session-persistence.spec.ts`: an expired access cookie keeps the person signed in, and Sign out ends it.
  - Full desktop Playwright run passed (77).
  - One full desktop + mobile run had `student-search` fail in both projects. It passes on its own and in the desktop-only
    run; the repeat run was stopped, so CI is the check.

## Checkpoint WID — 2026-11-18 — Webinar-ID-only monitoring; Zoom owns schedules; actual runs create attendance

- **Branch / PR:** `feat/webinar-id-auto-tracking` → PR to `main`, **not merged**. Specs:
  - `docs/product/ZOOM-WEBINAR-ID-ONLY-ACCEPTANCE.md`
  - `docs/architecture/ZOOM-WEBINAR-AUTO-SESSIONS.md`
  - `docs/delivery/WEBINAR-AUTO-TRACKING-IMPLEMENTATION.md`
- **WID-00 audit:** the webhook inbox, worker, per-UUID evidence, reports and identity matching were reusable. The one
  schedule dependency was `OccurrenceLinker`, which linked a run only to a LUMA SCHEDULED class within ±60 min.
- **WID-01/02 (monitoring and sync):**
  - `zoom_webinar_monitors` (V29) and `POST/GET /api/v1/zoom/monitors`; sync, stop, resume and academy-class routes.
  - Staff give only an ID or link. Zoom Get a Webinar (read-only) copies title, timezone, schedule (null when none) and
    occurrences; sync runs every 30 min and on demand. A failed sync keeps the last copy, marked stale. No Zoom write calls.
- **WID-03/04 (runs and visits):**
  - `zoom_webinar_runs` holds one run per occurrence UUID; `WebinarRunTracker` is called from the existing event handler.
  - Each run gets its own class (`origin = ZOOM_RUN`), created by `ZoomRunSessions` and retimed to the actual start and end.
  - An advisory lock per occurrence keeps processing idempotent. An ended run never reopens, and a missed start is not
    invented.
  - Join/leave evidence is unchanged and keyed by UUID; time is the union of intervals bounded by the actual run.
- **WID-05 (reconciliation):** the report worker also fetches runs that never sent `webinar.ended`, and the report closes
  them at the last leave. Dashboards count a live class by time held so far.
- **WID-06 (Academy roster):**
  - Students are expected only when the Academy class is certain (exact title match or an admin's choice) and set up as
    a batch. Otherwise no expected students, so nobody is marked absent.
  - An admin's choice also reaches the run in progress.
  - Identity: email, registration or an audited decision only.
- **WID-07 (UI):**
  - New Webinars page (nav: Teaching): ID/link-only add form, Live now, read-only Zoom timetable, Academy class, runs.
  - A class page shows its Zoom run (scheduled vs actual).
  - Removed: schedule, series, edit, cancel and import forms and their actions (`/staff/zoom/import` redirects).
  - Scheduling, editing, cancelling and import routes answer 409 unless `LUMA_SESSIONS_MANUAL_SCHEDULING=true` (tests only).
  - The webhook allow-list admits monitored webinars.
- **Tests (local, this Mac):**
  - Backend full suite: 436 run, 435 pass. The 1 failure is `ZoomWebhookDisabledIntegrationTest`, which fails on unchanged
    `main` too because the local ignored `.env` sets a webhook secret; CI has none.
  - New backend tests:
    - `WebinarMonitorIntegrationTest` (9): early start, late start, unscheduled, Zoom change, 3 days = 3 runs,
      duplicate/out-of-order, missed start, missed end closed by report, ambiguous title, admin decision,
      authorization/cross-academy, stop keeps history, 35 + 45 = 80 min with an overlap.
    - `ManualSchedulingOffIntegrationTest`.
  - Playwright: 147 passed, 3 skipped by design. New Webinars test; the series spec is replaced by a "no scheduling controls"
    check.
  - Real stack: 2/2 (classes created through the API with scheduling switched on for that backend).
  - Unit tests: 76. Session smoke: passes. Also passing: OpenAPI (122 endpoints), structure check, lint, build.
- **Not verified / external blockers:** no real Zoom proof yet. Needed:
  - the existing app's webinar read scope;
  - subscriptions to `webinar.started`, `webinar.ended`, `webinar.participant_joined` and `webinar.participant_left`
    (already used);
  - an authorized test webinar to start early.
  - Also: the Academy database role still has to be recreated before Academy classes resolve.
- **Rollback:** V29 is additive; reverting the code leaves the tables unused. Classes created by runs remain readable
  history.

### WID review fixes — 2026-11-19

Found by reviewing the WID branch and CI, and fixed with tests:

1. **Unresolved runs returned 404.** A run whose Academy class is unresolved has a class with no batches, which even
   owners and administrators could not open, so the Attendance links failed. Owners and admins now see every class;
   batch-scoped staff still see only their batches' classes.
2. **A webinar added while running started late.** Its run began at the next event, after the webinar had started.
   Adding or resuming a webinar now applies the stored events of its runs from the last 24 h, so the run starts at Zoom's
   actual start.
3. **Stopping monitoring stranded a live run.** The run waited 12 h for the report fallback. Its remaining events,
   including the end, are now still applied.
4. **Live runs showed a false 12-hour range.** Staff Classes, Today and the student portal now say "live since 16:00".
   Student class views carry `origin`.
5. **CSV import stuck before page load.** A file chosen before the page finished loading was lost and "Check file" stayed
   disabled. This was the cause of the recurring `student-search` CI failure; the form now picks the file up on load.
6. **Webinars page missed new starts.** It refreshed only while a run was live; it now refreshes while open (60 s, 30 s
   when live).
7. **Stopped webinars flagged stale.** A stopped webinar's copy is no longer shown as stale.
8. **`.env`-dependent test.** `ZoomWebhookDisabledIntegrationTest` no longer depends on a developer's local `.env`.

Tests:

- Backend full suite: 439 run, 438 pass before item 8; the remaining failure was item 8, now fixed and passing on its own.
- `WebinarMonitorIntegrationTest`: now 12 tests.
- Playwright: 147 passed, 3 skipped by design.
- Real stack: 2/2. Unit tests: 79. Smoke, lint, build: pass.

## Checkpoint UX-1 — 2026-11-19 — Frontend UX overhaul (staff and student)

Branch `feat/luma-frontend-ux-overhaul`, frontend only. No backend contracts changed. Details, audit table and
screenshots are in `docs/ux/UX-OVERHAUL-2026-11.md`.

- Staff navigation is grouped Overview / Teaching / Academy / Administration. Attendance is one section with tabs
  (To do · Reports · Batch totals · Rule & settings). `/staff/dashboard` stays as the Reports tab.
- Today puts live Zoom runs first, then work waiting, today's classes and recently finished runs. The KPI tiles that
  repeated Reports were removed.
- Webinar cards show status first; upcoming dates, management and run history are disclosures. Stopped webinars stay
  listed.
- Zoom review, Students and batch pages use disclosures. Zoom review dropped from 5046 px to 2683 px on desktop.
- Student home: the live or next class, a compact summary, then one link to `/student/attendance`, which now holds
  the final attendance records and their appeals.
- Outdated texts about "scheduling" or "importing" classes were replaced with the webinar-ID workflow.
- No horizontal overflow at 1440, 1280, 768, 390 or 320 px. Most touch targets are now at least 24 px.

Tests:

- Lint, typecheck and build: pass.
- Unit tests: 79 pass.
- Smoke test: pass.
- Playwright: 147 passed, 3 skipped by design.
- Real stack: pass.

Next: consider collapsing the Team and Courses forms, and enlarging the inline links on Zoom review.


## Local startup verification — 2026-11-19 (Windows)

- Installed frontend dependencies with `npm ci` (397 packages audited, zero reported vulnerabilities).
- Stopped the unrelated SwiftDrop frontend/gateway using ports 3000/8080 at the user's request.
- Started Next.js at http://localhost:3000 and Spring Boot at http://localhost:8080, with isolated local PostgreSQL 17 on port 5433. All 29 local migrations applied; readiness returned UP and frontend HTTP returned 200.
- Existing root environment points to hosted services. The first backend attempt loaded it and was stopped; subsequent startup explicitly overrides config import and uses local database settings with Academy payments, mail and Zoom integrations disabled.
- No application code or contracts changed. No full test suite run; verification covers startup and HTTP availability only. Fresh local database has no owner account, so sign-in requires owner bootstrap. Integrations were not verified.
- No deployment performed. Stop the local development processes/container to roll back; the local database volume persists. Development signing/MFA keys are ephemeral.

## PCA logo reuse — 2026-11-19

- Reused the existing unchanged PCA image as the sole navigation logo across public, staff and student shells through `AcademyBrand.tsx`; removed the separate LUMA symbols/wordmarks and duplicate public logo tile. Updated shared logo sizing in `globals.css`.
- Verified sign-in in Edge at 1440px and 390px: one decoded logo and no horizontal overflow; inspected the mobile screenshot. ESLint passed. TypeScript verification recorded below after completion.
- No API, authorization, contracts or diagrams changed. Authenticated staff/student views were not exercised in the browser; both use the same shared logo component. No deployment; rollback is reverting the component/shell/style changes.
- Typecheck: `npm run typecheck` passed.

## Premium visual refresh — 2026-11-19

- Reworked public `Shell.tsx` into a compact PCA header/footer; redesigned `app/page.tsx` with an editorial hero, CSS orbital illustration and glass portal cards. Added sign-in context in `sign-in/page.tsx`. `globals.css` adds a procedural grain texture, translucent surfaces, restrained entrance/orbit animations and shared portal surface styling. PCA remains the navigation logo.
- Checks passed: ESLint, TypeScript, Edge browser home at 1440/768/390/320px without horizontal overflow; both student/staff links reached sign-in. Reduced-motion emulation disabled orbital animation. Desktop/mobile screenshots reviewed; fixed clipping on floating illustration notes.
- No backend/auth contracts changed. Authenticated portal screens and full end-to-end suite not exercised; shared portal changes are surface styling only. No deployment. Rollback: revert this slice's page, shell and CSS changes; no database changes.

## Attendance landing copy cleanup — 2026-11-19

- Simplified `app/page.tsx` to “Track your attendance.” and student/staff sign-in cards. Removed learning-portal messaging, slogans, explanatory paragraphs and orbital illustration. Retained the PCA logo, glass surfaces and textured background. Updated the public footer in `Shell.tsx` and compact landing spacing in `globals.css`.
- Passed: targeted ESLint; Edge checks at 1440/390/320px for heading, removal of old copy and no horizontal overflow; both portal links reach sign-in.
- No backend/contracts changed; full suite and authenticated views not rerun. No deployment or database changes; rollback by reverting this slice's UI edits.

## Local version history workflow - 2026-11-19

- Created `feat/pca-attendance-ui` from main and recorded the prior local startup, logo reuse, visual refresh and attendance copy cleanup as four focused commits.
- Added the owner's standing instruction to `AGENTS.md`: use a descriptive branch and automatically commit each small verified change; push only on explicit request.
- Verified commit scope and whitespace with Git; no application behavior changed in this workflow slice. Prior UI checks remain recorded above. No deployment. All commits are local.

## Extension root-attribute hydration warning - 2026-11-19

- Branch: `fix/extension-hydration-warning`. The reported stack points to an extension content script adding `crxlauncher` / `crxlauncher-bridged` on the document root before React hydration.
- Changed only `frontend/src/app/layout.tsx` to suppress unavoidable hydration warnings on the root HTML element, with a comment explaining the scope. No application content or authentication behavior changed.
- Passed: targeted ESLint and TypeScript. Edge response-mutation verification injected both reported attributes before hydration: zero hydration warnings on HTML; the same injected attributes on a nested H1 still produced a hydration warning. Test browser disabled Edge local-network checks after the initial control failed to hydrate under that browser restriction. Actual installed extension was not controlled or modified.
- No full suite, deployment or database changes. Limitation: other root-attribute mismatches are also suppressed. Rollback: remove the root suppression and explanatory comment.

## Landing classroom animation - 2026-11-19

- Branch: `feat/landing-classroom-animation` (from `fix/extension-hydration-warning`). Added `frontend/src/components/ClassroomScene.tsx`, a decorative, `aria-hidden` inline SVG: a teacher points at a whiteboard (kinematics formulas and a projectile curve are written in), while three students take turns raising a hand ("Present, sir!") and each answer ticks a row on a small attendance register. A single 10.5 s CSS loop with no client JavaScript; the ball uses SVG `animateMotion`. Fictional figures only, no real names or data.
- `app/page.tsx` now places the scene beside the intro and sign-in cards (two columns at 900 px and wider; below the cards on narrower screens). `globals.css` adds the `.classroom-scene` / `cs-*` styles and widens `.public-home` to 1160 px. Under reduced motion every scene animation stops and a still frame is shown.
- Fixed the stale landing e2e step in `tests/e2e/student-portal.spec.ts` (it looked for the removed "I’m a student" link; now "Student sign in").
- Passed: targeted ESLint, `npm run typecheck`. Edge (Playwright, dev server): no horizontal overflow at 1440/1024/390/320 px; loop frames inspected at 1.6/4.5/7.6 s; reduced-motion emulation reports 0 running animations. The only console error is the existing missing `/favicon.ico` (404).
- Not run: full Playwright suite (including the edited landing step), unit tests. No backend, contract or database changes; no deployment. Rollback: revert this commit.

## Public pages fit the viewport - 2026-11-19

- Branch: `feat/landing-classroom-animation`. CSS only (`frontend/src/app/globals.css`). The public shell is now a full-height column (header, main, footer). The main area centres sign-in, forgot-password and the other account forms, and the landing hero, vertically and horizontally. Header, card padding, heading, inputs and gaps scale with viewport height (`clamp(..vh..)`), so the sign-in card no longer gets cut off at the bottom of laptop screens.
- Before the change, `globals.css` in the working tree had been re-saved with CRLF line endings and about 1.5 KB of trailing NUL bytes after the previous commit. The CSS rules were identical (verified with a diff that ignores those bytes), so the file was restored from HEAD.
- Verified in Edge (Playwright against the dev server), with no horizontal overflow anywhere: staff sign-in with the error message, student sign-in, plain sign-in and forgot-password show no vertical scroll and a card offset of 0 px from centre at 1920x969, 1536x730, 1440x900, 1366x657, 1280x600, 390x844 and 360x740. The only exception is 320x568 with the error showing (33 px of scroll). Landing: no scroll at desktop sizes; it scrolls on phones by design (scene below the cards). Screenshots reviewed.
- Not run: the full e2e suite. Authenticated portals use `.shell`, which is unchanged. No backend changes, no deployment. Rollback: revert this commit.

## Session redirect loop fix (2026-11-19)

The BFF now validates the access token against the identity endpoint before it uses the session for portal redirects. After the local backend switched databases and signing keys, an older sealed cookie could otherwise keep sending the browser from sign-in back to a portal that rejected the token. The HTTP smoke test covers a rejected token inside a valid sealed cookie and checks both the sign-in page and the session status endpoint.

Validation passed on Windows with Next.js 16.4.6: `npm test` (79 tests), `npm run build`, and `npm run test:smoke`. Targeted ESLint passed on both changed frontend files. Live Supabase sign-in and MFA were not exercised by the smoke test. The change adds an identity lookup to authenticated frontend requests. No schema or cookie format changed. Revert the frontend commit to roll back.

## MFA Cancel button contrast (2026-11-19)

Public authentication forms now keep the light secondary button background so the navy Cancel label is readable in both MFA states. Verified the computed secondary colors and primary gradient in Edge using the running sign-in page and the MFA button classes. Full MFA sign-in was not rerun. This stylesheet-only change requires no migration. Revert the commit to roll back.

## Staff Home redesign (2026-11-20)

Staff Home focuses on today’s schedule, confirmed live webinars and actionable attendance reviews. Analyst-only accounts get a direct link to batch totals. The Home sidebar uses a lighter palette, the overview link is labelled Home, and entry motion respects reduced-motion preferences. Removed weekly totals, recently finished webinars and Zoom troubleshooting counts from Home. The existing destination pages still provide those workflows. Role checks and API contracts are unchanged.

Verified the Next.js production build and the Staff Home Playwright spec on desktop Edge and Pixel 7 emulation with the mock backend (both passed). The bundled Chromium executable was missing on this machine, so verification used installed Edge. Live Supabase sign-in was not exercised for this slice. There are no migrations or deployment settings. Revert this commit to roll back.

## MFA removed: email and password sign-in for every role (2026-11-20)

- Branch: `feat/remove-mfa`. Owner decision: staff and everyone else sign in directly with email and password; the TOTP second factor (001F/001G, every staff role since FIX-3) is gone. Risk accepted by the owner: a leaked or guessed staff password alone now opens that staff portal.
- Backend: `AuthService.login` and `studentIdLogin` return session tokens; `MfaService`, `Totp`, `SecretCipher`, `Base32`, `InvalidMfaException`, the `/auth/mfa/enroll|verify|reset` routes, the MFA rate-limit bucket and `LUMA_AUTH_MFA_ENCRYPTION_KEY` are removed. Bearer authentication and refresh no longer require an MFA-verified session for owners and administrators. `StaffAccountView` drops `mfaEnrolled`; the reset email no longer mentions an authenticator.
- Database: no migration. The V6 tables and `refresh_sessions.mfa_verified` (default false) stay but are unused; student erasure still clears them. Dropping them would be a separate, irreversible migration.
- Frontend: removed `/sign-in/mfa`, `/sign-in/mfa/recovery-codes`, `CopyRecoveryCodes`, the `mfa-verify|mfa-cancel|mfa-recovery` session actions and the `luma-mfa`/`luma-recovery` cookies; the team pages no longer show "2-step on/off". Contract, auth diagram, JWT-AUTH, staff guide, runbooks, smoke scripts and the key generator updated.
- Passed: full backend suite with Testcontainers (`mvn test`, 410 tests, 0 failures); frontend `npm test` (73), typecheck, lint, production build, `npm run test:smoke`; Playwright desktop suite in installed Edge against the mock backend: all specs pass except `attendance-dashboard`, which fails because commit 90611f7 removed the "Attendance reports →" link from Staff Home (unrelated to sign-in, not fixed here). OpenAPI lint: 112 errors vs 114 before (none new).
- Not run: mobile Playwright project, `scripts/auth-stack-smoke.sh` and `scripts/staff-ui-real-stack.sh` (need bash + shasum/real stack), live Supabase sign-in. Staging Terraform still creates the unused `auth-mfa-encryption-key` secret (left to avoid destroying a resource on apply).
- Rollback: revert these commits; previously enrolled authenticators would work again because their rows were not deleted.

## Staff Home dashboard: live class, day view and month calendar (2026-11-20)

- Branch: `feat/staff-home-dashboard` (from `feat/remove-mfa`). Owner request: replace the purple Staff Home with a simple, modern page in the logo's colours (navy, royal blue, sky) showing live class numbers, the day's classes with day-to-day navigation, and the month's classes as in Zoom.
- `/staff` now shows, top to bottom: a live card per running class (at most three) with Expected, Joined, Online (Zoom's connected count when Zoom reports the run live, else students with an open visit) and attendance % (joined of expected) as an animated ring, refreshed every 30 s with a Pause control; when nothing runs, the next class with "starts in …". Then the chosen day's classes (`?day=`, previous/next/Today) with Live/In progress/Upcoming/Ended and, for finished classes, "joined of expected · %" from the attendance dashboard; Needs attention (managers). Then a Monday-first month calendar (`?month=`) of timetable classes plus, for owners/administrators, Zoom occurrences of monitored webinars that no class covers yet (same webinar within an hour counts as covered).
- Titles stay on one line (`OneLineTitle`): the full text is still the link's name; when cut off, hover or keyboard focus shows it in a small card (Escape hides it); touch users open the class page. Motion (entrance, live ping ×3, ring fill, tooltip fade) only under `prefers-reduced-motion: no-preference`. The Home sidebar uses the standard navy menu again. Instructors/attendance officers see their classes; "In progress" (not "Live") is used when Zoom's live list is unavailable to the role. Analysts keep the reports entry.
- Data: existing endpoints only (`class-sessions` over the calendar grid ≤ 42 days, `zoom/monitors`, `class-sessions/{id}/attendance`, `attendance/dashboard` for the chosen day). No backend or contract change.
- Tests: `tests/staff-home.test.mjs` (6) for the pure helpers in `src/lib/staff-home.ts`; `tests/e2e/staff-overview.spec.ts` rewritten (day navigation, calendar, one-line titles with hover/Escape, live card); `attendance-dashboard.spec.ts` follows the restored "Attendance reports" link. Screenshots checked in Edge at 1440 px and 390 px with no horizontal overflow.

### Staff Home, second pass (2026-11-20, owner feedback: "looks AI-made")

- Calmer design: flat white cards, hairline borders, 12px corners, sentence-case labels, no gradients or glow. The staff portal now uses Plus Jakarta Sans (self-hosted by `next/font`, so the CSP's `font-src 'self'` still holds).
- "Needs attention" removed from Home (its tasks remain on the Attendance page). The top strip is compact: one short row per live class (joined of expected, attendance %, online) or the next class.
- Day and month navigation: previous on the left, the current day/month in the middle (with "Back to today"/"Back to this month"), next on the right.
- New "Attendance in <month>" column chart (`AttendanceBars`): one column per class held so far in the calendar's month, joined of expected students, the average as a line, a readout on hover/focus, and "Show as table". Classes with nobody expected are counted in a note, not drawn. Streamed with `Suspense` so the month's sums don't delay the page.
- Fix: the home grid's single column is now `minmax(0,1fr)`, so a long one-line title cannot widen the page on phones (caught by the phone e2e run; the spec now checks overflow right after adding a long title).

## Staff Home monthly attendance average (2026-11-20)

- Staff Home shows a single bar for the selected month's average. Each completed class with expected students contributes its joined-to-expected percentage with equal weight. The UI rounds only the final average and shows how many classes were counted. Classes with no expected students are excluded, while a class with expected students and zero recorded joins contributes 0%.
- Staff Home now uses `GET /api/v1/attendance/class-totals` for the selected day and month. The previous chart loaded the detailed attendance matrix and reported an error on the Supabase-backed page. The new route batches enrolment reads, uses the existing matched Zoom visits, and returns only each completed class's ID and expected and joined counts. It uses the same academy and batch access checks as the detailed attendance dashboard and includes every completed class in range. Updated the OpenAPI contract and authorization matrix.
- Backend Testcontainers passed 19 tests in `AttendanceInsightsIntegrationTest` and `ClassSessionHttpIntegrationTest`. Coverage includes duplicate joins and shared enrollments, completed-class filtering, no expected students, invalid ranges, and cross-role, batch and academy isolation. Frontend unit tests (81), typecheck, targeted lint, and the isolated production build passed. Playwright passed 12 Staff Home and Attendance Dashboard tests on Edge desktop and Pixel 7 emulation with the mock API. The OpenAPI contract parsed and `scripts/verify_structure.py` passed.
- The local backend restarted with the original Supabase database. Readiness returned UP, the new route returned 401 without a session, and the frontend sign-in page returned 200. The Windows browser tool could not determine the Chrome URL, so the signed-in Supabase chart was not verified in that browser.
- No migration is needed. Roll out the backend before or alongside the frontend so Staff Home can reach the counts route. Roll back both together by reverting this commit.

## Staff Home class summaries and full titles (2026-11-20)

- When no class is running, Staff Home displays the next class and the last completed class with expected and joined counts and the percentage who joined. A class with nobody expected shows a dash for the percentage. While a class is running, the top section shows only running classes, including a class that started before midnight and is still running.
- Full titles open in a viewport-clamped popover above adjoining cards when the link is hovered or keyboard-focused. Escape dismisses the popover. The link keeps its full accessible name.
- `GET /api/v1/attendance/latest-completed` selects the latest non-cancelled class by end time within the caller's academy and readable batches. It uses the existing enrollment and matched Zoom visit rules and returns 204 when no completed class is visible. The OpenAPI contract and authorization matrix document the route.
- Targeted Testcontainers passed 20 tests in `AttendanceInsightsIntegrationTest` and `ClassSessionHttpIntegrationTest`. Frontend unit tests (81), TypeScript, targeted lint, and an isolated production build passed. Playwright passed four Staff Home tests on Edge desktop and Pixel 7 emulation, plus four tests in a combined Staff Automation and Staff Home run on desktop, using the mock API. The OpenAPI contract parsed and the structure check passed.
- Restarted the local API with the original Supabase database. Readiness returned UP, the new route returned 401 without a session, and the sign-in page returned 200. The signed-in Supabase page was not verified.
- No migration is needed. Roll out the backend before or alongside the frontend and revert both together to roll back.

## Staff Home attendance for the last five classes (2026-11-20)

- Replaced the monthly average with a horizontal bar per completed class, newest first. The chart covers the latest five classes across dates, independent of the calendar month, and displays each class title, end date, joined and expected counts, and the percentage who joined. Classes without expected students show a dash and an empty track. The first result also supplies the last-completed card when no class is live.
- `GET /api/v1/attendance/recent-completed` applies academy and batch scope before selecting at most five non-cancelled classes by end time. It reads expected rosters in one batch and uses matched Zoom visits. The existing latest-completed and date-range endpoints remain available. Updated the OpenAPI contract and authorization matrix.
- Passed 20 targeted Testcontainers tests, 79 frontend unit tests, TypeScript, targeted lint, an isolated production build, and four mock API Playwright tests on Edge desktop and Pixel 7 emulation. Browser coverage includes class order across dates, calendar independence, displayed rates, and empty rosters. The OpenAPI contract parsed and the structure check passed.
- The local API restarted with the original Supabase database. Readiness returned UP, the new route returned 401 without a session, and the sign-in page returned 200. The signed-in chart against Supabase was not verified.
- No migration is needed. Roll out the backend before or alongside the frontend so Staff Home can use the new route. Revert both together to roll back.

## Compact Staff Home class cards (2026-11-20)

- Next class and Last completed class use equal-width columns when the content area can fit both cards and stack on narrow screens. The class title uses smaller type and one line. The existing full-title popover stays available on hover and keyboard focus.
- The next card puts its time and countdown in a separate row. The last completed card keeps Expected, Joined, and Attendance in three columns below the title and end time. Live rows and the five-class chart retain their layouts.
- TypeScript, targeted lint, the structure check, and an isolated production build passed. Four Staff Home Playwright cases passed on Edge desktop and Pixel 7 emulation with the mock API, covering card alignment, metric containment, title tooltips, and the live view. Checked the desktop screenshot. The local API reported UP and the sign-in page returned 200. The signed-in Supabase layout was not verified.
- This change is frontend-only and needs no migration. Revert the commit to roll back.

## Staff Home dashboard refinement (2026-11-20)

- The dashboard groups the day schedule and calendar next to the five-class attendance chart on desktop. It stacks the content on smaller screens. The palette uses blue for schedule controls, teal for attendance values, and colored class status labels. Daily rows show the status once and keep joined counts or Zoom schedule details when available.
- Calendar dates and overflow links take staff to the selected day’s schedule. Compact calendar cells provide a full-cell touch target. Day and month arrows provide pending feedback, and dashboard links have a visible keyboard focus outline.
- Class names in Staff Home show the first two nonempty leading parts when the title has at least two `|` separators. Titles with fewer separators keep their text. The full title stays in the accessible link name and appears in the popover on hover or keyboard focus, including when the shorter label fits.
- Passed 79 frontend unit tests, TypeScript, targeted lint, an isolated production build, and the structure check. Four Playwright Staff Home cases passed on Edge desktop and Pixel 7 emulation with the mock API. Inspected both full-page captures for layout and overflow, then verified the compact calendar legend and full-cell targets in two focused browser checks, including a mobile tap. The local API reported UP and sign-in returned 200. The signed-in Supabase dashboard was not verified.
- Frontend-only change. Rollback is a revert of this commit. No database migration is needed.

## Staff Home live online chart and split layout (2026-11-20)

- Running classes show a chart of the observed online count beside the live figures. It uses the existing 30-second refresh and its Pause control, with no extra requests. It keeps up to 30 minutes of observations in the open page for that class. Missing reads retain the last observations with an unavailable state.
- Today’s schedule and the last-five attendance chart stack on the left at desktop widths, with the calendar on the right. The two columns align at the bottom. Narrow screens use one column, and the live chart moves below the figures when space is limited.
- Passed 79 frontend unit tests, TypeScript, targeted lint, the structure check, and an isolated production build. Six Staff Home cases passed in desktop Edge and Pixel 7 emulation with the mock API, including an observed count change from 12 to 17 after resuming refresh and the two-column alignment. Two focused browser checks verified the final live row width. Reviewed desktop and mobile screenshots. The local API reported UP and sign-in returned 200. A signed-in Supabase webinar was not tested.
- Frontend-only change. Revert the commit to roll back. No database migration is needed.

## Staff Home calendar class previews (2026-11-20)

- Date links show a hover preview with each scheduled class title and time range. Medium-width calendar cells show the first two class start times and an overflow count. Narrow cells keep the number of classes, and wide cells keep their class links. Selecting the date still opens that day’s schedule.
- TypeScript, targeted lint, the structure check, and two targeted Staff Home Playwright cases passed with the mock API on desktop Edge and Pixel 7 emulation. The local API reported UP. The signed-in Supabase calendar was not verified.
- Frontend-only change. Revert the commit to roll back. No migration or schedule data changes.

## Staff Home live chart scale and motion (2026-11-20)

- The online chart scales to the observed count range with padding, including zero when it was recorded. Each 30-second refresh draws the newest segment and fades in the new point. Reduced-motion preferences disable the animation.
- The timeline starts at class start and shows the scheduled end when known. For an open-ended class it extends to the latest observation, labeled to the second. The chart retains observations from the current page for the run, bounded to 1,441 points. Time before the first observation stays empty.
- Passed 79 frontend unit tests, TypeScript, targeted lint, the structure check, an isolated production build, and the live chart Playwright case in desktop Edge and Pixel 7 emulation with the mock API. A focused check verified the actual axis boundary for a run that started only seconds earlier. The local API reported UP and sign-in returned 200. A signed-in Supabase webinar was not tested.
- Frontend-only change. Revert the commit to roll back. No database migration is needed.

## Staff Home current-time chart and compact calendar times (2026-11-20)

- The live chart now runs from class start to the latest refresh time, labeled `Now` to the second. If a count is unavailable, the time axis still advances and the chart ends at the last observed count. Existing scaling and reduced-motion behavior apply.
- Compact calendar cells now show up to two actual class start times at all widths and an overflow count when needed. Hovering the date displays the names and times. Selecting it still opens the day schedule. Wide calendar cells keep their class links.
- Passed 79 frontend unit tests, TypeScript, targeted lint, the structure check, an isolated production build, and four targeted Staff Home Playwright cases in desktop Edge and Pixel 7 emulation against the mock API. The cases cover both current-time axes, the refresh animation, compact start times, hover preview text, and date navigation. Layout measurements use reduced motion so they check the settled card positions.
- Restarted the local frontend and the API with the original Supabase connection. API readiness returned UP and the sign-in page returned HTTP 200. The signed-in Supabase dashboard was not verified.
- Frontend-only change. Revert the commit to roll back. No migration or schedule data changes.

## Staff Home live online history from Zoom evidence (2026-11-20)

- The live dashboard asks for `includeOnlineHistory=true` on the existing class attendance API. It reconstructs 30-second samples from stored Zoom visits, merges simultaneous visits by student, and includes a final sample through the current count or the end of available evidence. A newly opened or reloaded tab shows earlier observations immediately. Time before the first visible join stays empty. Missing history shows an unavailable state and a temporary fetch failure keeps the last successful plot.
- The graph and Students online figure count matched students. Managers can see matched students across the academy for the class, while assigned staff see only the class roster in their readable batches. The history contains counts and timestamps. The route’s existing scope checks also apply to the history.
- Passed 16 targeted backend tests in `OnlineHistoryTest`, `ProvisionalAttendanceIntegrationTest`, and `AttendanceInsightsIntegrationTest`. Coverage includes Zoom reports, rejoins and overlaps, missing joins, end-time clipping, the twelve-hour bound, and academy and batch scope. Passed 79 frontend unit tests, TypeScript, lint, the structure check, and an isolated production build. Six Staff Home Playwright cases passed on Edge desktop and Pixel 7 emulation with the mock API, including pre-existing history on first visit and hard reload, refresh animation and pause, and no-evidence behavior. OpenAPI YAML parsed.
- Restarted the local API with the original Supabase connection. Readiness returned UP, the attendance history request returned 401 without a session, and sign-in returned 200. The signed-in Supabase dashboard and live Zoom delivery were not exercised.
- Roll out the backend before or alongside the frontend. No migration is needed. Revert both together to roll back.
## Two roles: STAFF and STUDENT (2026-11-20)

- Branch: `feat/two-roles` (from `0674ff1`), worked in the worktree `.claude/worktrees/two-roles` because the main
  checkout had another session's uncommitted "latest completed class" work. Plan and progress table:
  `docs/delivery/TWO-ROLES-REFACTOR-PLAN.md`; decision: `docs/architecture/adr/ADR-0007-two-roles.md`.
- Owner decision: only two roles. `STAFF` does every staff action in its academy; `STUDENT` is unchanged. The five staff
  roles and batch assignments are gone. Risk accepted by the owner: every staff member can disable colleagues, erase a
  student's data and change the attendance rule; the audit log records who did it.
- Database: `V30__two_roles.sql` gives each account holding any former staff role one `STAFF` row (earliest grant kept),
  deletes the old rows and narrows the CHECK to `STAFF`/`STUDENT`. An account that also held `STUDENT` keeps it.
  `staff_assignments` is kept for history (table comment) but no longer read or written.
- Backend: `Role { STAFF, STUDENT }`; `CurrentAccount.isStaff()/requireStaff()` replace the manager checks;
  `SecurityConfig` has one `hasRole("STAFF")` rule for every staff route. Batch scoping, `BatchScope` and the assignment
  entity, service, controller and routes (`/batches/{id}/assignments`, `/assignments/{id}`, `/me/assignments`) are
  removed. Former owner-only (rule, engagement switch, erasure) and role-specific actions (corrections, pulses, academy
  totals) are staff actions. Staff accounts: create takes only an email (any `roles` sent is ignored),
  `PUT /staff-accounts/{id}/roles` is removed, nobody disables themselves and the academy keeps one active staff account.
  Auto-finalisation runs as the staff member who switched it on while they are active staff. Bootstrap creates the first
  `STAFF` account and refuses when the academy has active staff (command name and env vars unchanged).
- Frontend: `isStaff` replaces `isAcademyManager`; the staff portal opens for `STAFF` only. Dead "only owners and
  administrators" banners are removed from 11 pages; Home, Attendance, Classes, class page, batch roster and dashboard
  show every section to every staff member; Team has no role pickers (email only) and the batch roster no assignments.
  The activity log still names roles recorded before the change. Sealed session cookies are unaffected: pages read
  roles from `/auth/me` on every request.
- Contract and docs: OpenAPI (assignment and roles routes removed, `StaffRole` = `STAFF`, summaries say "staff"),
  authorization matrix (current model on top, earlier notes kept as history), JWT-AUTH, README, PRD, overview, schema
  plan, staff guide, first-staff and webhook runbooks, sign-off sheet, Zoom rules, load-test and smoke scripts. Rollback
  aid: `docs/delivery/runbooks/TWO-ROLES-ROLLBACK.sql`.
- Passed: backend `mvn test` with Testcontainers PostgreSQL 17, 402 tests, 0 failures (includes
  `TwoRolesMigrationIntegrationTest`: V29 data with every former role migrated by V30, and `BatchAccessServiceIntegrationTest`);
  frontend `npm test` (79), typecheck, ESLint, production build, `npm run test:smoke`; Playwright in installed Edge
  (bundled Chromium missing) against the mock backend, desktop and Pixel 7: 126 passed, 2 skipped (skips unchanged);
  `scripts/check-openapi.py` (115 endpoints match) and `scripts/verify_structure.py`.
- Not run: `scripts/auth-stack-smoke.sh`, `scripts/staff-ui-real-stack.sh` (real stack spec updated but not executed),
  `scripts/load-test-local.sh`, and the backend against the local Supabase database: starting it there applies V30 to
  real accounts, which needs the owner's go-ahead.
- Rollout: deploy backend and frontend together (the web app recognises only STAFF). Before rollout, review the staff
  list: every former instructor, attendance officer and analyst gains full staff access. Rollback: run
  `docs/delivery/runbooks/TWO-ROLES-ROLLBACK.sql` before starting older code, then revert these commits.
- Merge note: the main checkout's open "latest completed class" work (`GET /api/v1/attendance/latest-completed`) touches
  `SecurityConfig`, `ClassSessionService`, Staff Home, the contract and the authorization matrix; on merge the new route
  joins the single STAFF rule and its batch-scope code collapses to staff-wide access.

## One Classes screen; no Staff page (2026-11-20)

- Branch `feat/two-roles` (worktree `.claude/worktrees/two-roles`). Plan and progress:
  `docs/delivery/CLASSES-AND-STAFF-SIMPLIFY-PLAN.md`. Owner request: Webinars and Classes become one screen showing only
  the classes Zoom has scheduled and the ones held, with "track a Zoom link" on the same screen; and, with one STAFF
  role, no Staff page at all.
- Classes (`/staff/sessions`): "Track a Zoom link" (ID or join link only) at the top, the tracked links collapsed under
  it (refresh from Zoom, stop/track again, Academy class), then two weeks of classes grouped by day from the shared
  `calendarEntries` helper: Zoom's scheduled dates of tracked webinars ("Expected", not links) and held classes
  (Live/Ended, linking to attendance). The batch filter, live cards, run history and the "where classes come from"
  panel are gone. `/staff/webinars` and `/staff/zoom/import` redirect to Classes; the Webinars and Staff menu items are
  removed; webinar actions moved to `app/staff/sessions/webinar-actions.ts`.
- Staff page and API removed: `/staff/team` pages and forms; `GET/POST /api/v1/staff-accounts`, `GET /{id}`, `/enable`,
  `/disable` (now 403 by the deny-all rule); `StaffAccountService/Controller/View`, `Account.enable`,
  `AccountRepository.activeStaffOtherThan`. `POST /api/v1/accounts/{id}/invitations` stays
  (`AccountInvitationService`/`AccountInvitationController`) for pending accounts (students).
- Operator path: `scripts/bootstrap-owner.sh` now adds any staff member (new pending STAFF account and invitation, or a
  fresh invitation for a pending one) and refuses active, blocked, student or other-academy addresses. Blocking and
  unblocking are `docs/delivery/runbooks/sql/staff-block.sql` and `staff-unblock.sql` (runbook `STAFF-ACCOUNTS.md`):
  block ends every session, cancels unused activation links (the test caught that this was missing at first) and is
  audited without an actor. `staff-ui-real-stack.sh` adds the colleague with the command and checks the block SQL.
- Contract: staff-account paths and `StaffAccount`/`StaffRole` schemas removed; `check-openapi.py`: 110 endpoints match.
  Docs: authorization matrix, ADR-0007 follow-up, README, staff guide, FIRST-OWNER runbook.
- Passed: backend `mvn test` 393 tests, 0 failures (new `StaffAccountOperatorIntegrationTest` runs the runbook SQL files
  as written; bootstrap tests cover adding a colleague and the refusals); frontend `npm test` (77), typecheck, ESLint,
  production build; Playwright in installed Edge against the mock backend, desktop and Pixel 7: 118 passed, 2 skipped
  (unchanged skips); `scripts/check-openapi.py` (110 endpoints) and `scripts/verify_structure.py`.
- Not run: `scripts/staff-ui-real-stack.sh` (updated: the colleague comes from the operator command, the block SQL is
  checked by a refused sign-in), and nothing against the Supabase database.
- Rollout: as for the two-role change (backend and frontend together). Anyone who must add or block staff after this
  needs the operator command or database access.

## Integrated Claude’s Classes and staff-role changes with Staff Home (2026-11-20)

- Merged the committed `feat/two-roles` work through `202af21` into `integrate/claude-staff-dashboard`. The Claude worktree and its in-progress merge were left in place. The combined app keeps the single Classes screen, operator-managed staff accounts, and the dashboard’s current-time graph, saved online history, calendar previews, and recent attendance.
- The dashboard’s class totals, latest and recent classes, and online history require `STAFF`, with data limited to the authenticated academy. The history includes matched students who joined the class, including those outside its roster. Students retain own-record access. The OpenAPI contract and authorization matrix reflect these rules.
- Packaged the backend and ran the combined application on loopback with a separate PostgreSQL 17 preview database. Flyway applied V30 there. Verified activation and sign-in for a fictional preview staff account, the Staff dashboard and Classes pages, the Webinars redirect, the removed Staff page and API, and the recent attendance API. Backend readiness returned UP. Runtime preview uses debug logging disabled.
- A clean full backend run passed all 398 tests with Testcontainers and debug logging disabled. The first run failed one log-privacy assertion under an inherited debug setting. The OpenAPI checker matched all 112 routes, the structure check passed, and the frontend’s 75 unit tests, lint, production build, and session smoke passed. The focused desktop and mobile browser suite passed 13 cases with one expected skip.
- The broad mobile run completed with 58 passes, two expected skips, and one Staff Home failure caused by a running-class fixture left by the live-refresh test. Added fixture cleanup and verified the affected sequence on desktop and mobile. The broad desktop run was partial because of browser timeouts. The affected desktop academy and bulk invitation cases passed in isolated reruns.
- The original Supabase database was left unchanged. Applying V30 there would replace original staff role records with one academy-wide role. The branch’s rollback aid maps `STAFF` to `ACADEMY_OWNER` and cannot recover individual prior roles. Use a backup that preserves roles for faithful rollback. The combined frontend and backend need to roll out together.

## Original Supabase cutover (2026-11-20)

- After the owner requested the combined version on the original Supabase database, exported and verified a pre-V30 snapshot of all 289 `account_roles` rows outside the repository. The snapshot preserves the role names, account IDs, and grant timestamps for an exact role rollback. Retain it privately alongside the rollback runbook.
- The combined backend applied V30 through Flyway. Verified that the final `STAFF` account count matches the distinct former staff account count, the `STUDENT` count matches the snapshot, and no former role names remain. API readiness returned UP and the sign-in page returned 200. Signed-in access with an existing Supabase account was not tested.
- The frontend and API still run on loopback. Stopped the isolated demo database. Local background processing for Zoom, mail, retention, and auto-finalisation was disabled for this run.

## Classes month-to-date overview (2026-11-20)

- Kept the two-week Classes schedule and added an adjacent month-to-date list of class types. Narrow screens stack the panels. Expanding a type shows its completed session dates and times in Sri Lanka time, newest first. Grouping uses the visible class and batch title and ignores suffixes after the second pipe. Older timetable entries keep their scheduled-time label.
- Uses the existing academy-scoped class-sessions API. Passed 76 frontend unit tests, regenerated route types and TypeScript, targeted lint, and the structure check. The original Supabase-backed API reported UP, and sign-in returned 200. Signed-in browser rendering of the new panel was not verified.
- Frontend-only change. Revert the commit to roll back. No migration is needed.

## Classes panel order and scrolling (2026-11-20)

- The two equal-height Classes panels sit above the Zoom tracking form. Each list scrolls independently with its heading and controls still visible. On narrow screens the panels stack.
- TypeScript, targeted lint, and the structure check passed. Supabase-backed API readiness was UP and sign-in returned 200. A signed-in visual check was not run. Frontend-only change, reversible by reverting the commit.

## Attendance charts on Classes (2026-11-20)

- The two-week list displays joined-of-expected percentages for completed sessions, and the monthly class-type list displays an equal-weight average of its completed session percentages. A missing count or empty expected roster has no rate. Both use the existing scoped class totals endpoint.
- A separate animated column chart sits above each list. The charts support horizontal scrolling, descriptive labels, and reduced motion. The lists remain equal height with independent scrolling.
- Passed 77 frontend unit tests, TypeScript, targeted lint, and the structure check. The original Supabase-backed API reported UP and sign-in returned 200. Signed-in visual verification was not run. No migration is needed. Revert the frontend commit to roll back.

## Class type attendance detail (2026-11-20)

- The Classes tab links each monthly class type to a staff-only detail page with distinct active enrolments across its batches, an equal-weight average of completed session percentages, and an animated month-to-date trend. Each plotted point shows the session date, time, and rate on hover or focus and links to the attendance record. Student and completed session lists scroll independently and stack on narrow screens.
- The detail page uses the existing academy-scoped schedule, roster, and attendance APIs. Each student's rate uses sessions of that type where the student was expected. Missing rates leave gaps in the chart. The frontend proxy recognises the named route and still rejects malformed class record IDs.
- Passed 77 frontend unit tests, TypeScript, targeted ESLint, the structure check, and two focused signed-in Playwright checks in desktop and mobile Edge with mock data. The original Supabase-backed API reported UP. Its frontend redirected an unauthenticated detail request to staff sign-in and returned 404 for a malformed class record ID. Signed-in production data rendering was not tested.
- Frontend-only rollout. No migration is needed. Revert this commit to roll back.

## Class type roster controls and chart alignment (2026-11-20)

- The class-type detail excludes sessions with zero recorded joins from the completed list, trend, class average, and each student's expected-session denominator. Unavailable attendance still displays as unavailable. Sessions with actual joins and a rate that rounds to zero display as less than 1%.
- Added live name and registration-number search and name or attendance sorting above the roster. Unknown rates sort after known rates. The line and interactive points now share the same fixed plot bounds at desktop and mobile widths.
- Passed 77 frontend unit tests, TypeScript, targeted ESLint, and the structure check. Desktop and mobile Edge mock checks verified search, sorting, zero-join exclusion and averages, point focus, and line-to-point alignment within one pixel. The Supabase-backed API reported UP and sign-in returned 200. Signed-in verification with original data was not run.
- Frontend-only rollout. No migration is needed. Revert this commit to roll back.

## Individual session attendance overview (2026-11-20)

- Reordered the staff session page to show expected students, matched student joins, expected students with no recorded join, final attendance credit, duration, and mean time connected before the timeline and student roster. Attendance credit counts final Present and Late outcomes. Mean time connected uses students who joined. Legacy sessions label the scheduled duration and Zoom runs use the available actual timings.
- The interactive timeline uses all online samples reconstructed by the existing academy-scoped attendance API. Its range spans the session through the recorded end, or the current time while live. Missing sample coverage stays blank. Staff can inspect counts by pointer or keyboard, and the existing 30-second refresh keeps live results current.
- The scrollable roster shows student identity, connected time, first join, visits, and outcome or pending status. Finalisation, corrections, CSV export, Zoom details, and engagement are still available on the page.
- Passed 77 frontend unit tests, TypeScript, targeted ESLint, and the structure check. Focused desktop and mobile Edge checks with mock data passed for the 221-sample finished timeline, keyboard scrubbing, plot alignment, and live pause. The broader browser run passed 16 cases with three configured or midnight-window skips. A desktop Zoom replay sign-in timeout passed in isolation. One rerun hit a tracked-link disclosure timeout before reaching the session page. Supabase-backed readiness was UP and sign-in returned 200. No signed-in test against original data was run.
- Frontend-only rollout with the existing attendance API. No migration is needed. Revert this commit to roll back.

## Simplified individual session attendance (2026-11-20)

- The timeline draws a smooth curve through each recorded sample without overshooting adjacent counts. The timeline readout and keyboard scrubber still use the original sample values.
- Summary cards show labels and values only. The Final Attendance panel uses one roster with name and registration-number search, outcome filters, and name or connected-time sorting. Removed its explanatory text and recalculation control. Removed the duplicate Expected Students panel and legacy Zoom placeholder. The remaining session details provide links to batch rosters and linked occurrences.
- Passed 77 frontend unit tests, TypeScript, targeted ESLint, and the structure check. Desktop and mobile Edge mock checks covered the 221-sample timeline, point alignment and keyboard scrubbing, search by name and registration number, combined status filters, time sorting, correction, CSV export, and the legacy layout. The initial roster test used an incorrect select locator and the corrected attendance-rule suite passed on both devices. Supabase-backed readiness and sign-in returned successfully. Signed-in verification with original data was not run.
- Individual corrections and CSV export remain available from the main roster. No migration is needed for the frontend rollout. Revert this commit to roll back.

## Session attendance recovery on Supabase (2026-11-20)

- Supabase rejected runtime connections with `EMAXCONNSESSION` on the session pooler. The class shell loaded while the attendance request failed. The session attendance read now has a 25-second timeout for its roster and samples, failed reads use a neutral heading, and slow transport failures are logged without query strings, IDs, or credentials.
- Restarted the existing loopback API against the same Supabase database using a process-only JDBC override for the transaction pooler on port 6543 with `prepareThreshold=0` and `DB_POOL_MAX=8`. Used the already migrated schema with Flyway and the existing background workers disabled for this run. The original configuration files were preserved.
- Eight concurrent readiness checks passed before the switch and again on port 8080 after the switch. Frontend sign-in returned 200, an unauthenticated attendance request returned 401, and the replacement API log showed no pool errors during verification. Passed 77 frontend unit tests, TypeScript, targeted ESLint, the structure check, and two desktop and mobile timeline checks with mock data. Signed-in verification on the original session was not run.
- To retain the runtime fix on another restart, reapply the transaction-pooler JDBC override to the app process. Keep Flyway on a direct or session-pool connection for future migrations. Rollback uses the previous session-pool runtime connection and may restore the connection-limit failure. No database migration was made.

## Compact session attendance controls (2026-11-21)

- Removed the average connected-time summary card and the per-student correction control. The final roster offers All statuses, Present, Late and Absent filters. The filter and right-aligned status column use a fixed compact width on desktop, with a flexible layout on mobile.
- Passed 77 frontend unit tests, TypeScript, targeted ESLint, the structure check, and four desktop/mobile Edge checks against the mock backend. Updated the real-stack onboarding test for the new roster and final outcome display. The real-stack suite and the signed-in Supabase page were not run.
- The frontend change needs no database migration. Revert this commit to roll back.

## Simplified staff sidebar (2026-11-21)

- The sidebar lists Home, Classes, and Students. Removed the Attendance, Academy classes, Courses & batches, Zoom review, and Activity log tabs and the menu group headings. Supporting screens can still be reached through their existing links and URLs.
- Passed 77 unit tests, TypeScript, targeted ESLint, and the structure check. Desktop and mobile mock browser coverage passed 21 cases across the main run and the Activity log rerun after correcting a test fixture race. One shared Academy setup case was skipped on mobile by design. API readiness and sign-in smoke passed. Signed-in verification with original data was not run.
- Frontend-only change. Revert this commit to roll back.

## Shared monthly Classes schedule (2026-11-21)

- The month picker controls the schedule, class-type list, and charts together. Sri Lanka midnight bounds each calendar month. The current month's independently scrollable schedule opens at today's group, and older class-type pages keep their selected month on the detail page and back link.
- The class chart includes every ended class record from the selected month through today. Classes without a rate show a dash. Upcoming schedule entries do not appear in the attendance chart. Both panels share one schedule and one attendance totals request. Legacy date links still select the corresponding month.
- Passed 78 frontend unit tests, TypeScript, targeted ESLint, the structure check, and desktop/mobile mock browser checks for the legacy schedule and the new month picker, scroll position, leap-month boundaries, chart coverage, and detail links. API readiness and sign-in smoke passed. Signed-in verification with original data was not run.
- Frontend-only change with existing APIs. Revert this commit to roll back. No migration is needed.

## Standalone Class Types page (2026-11-21)

- Added Class Types to staff navigation. The standalone page reuses the monthly class-type list, attendance chart, and staff data requests from Classes. Opening a detail from the new tab preserves the month and returns to Class Types. The Classes panel still opens details in the Classes navigation context.
- Passed 78 unit tests, TypeScript, targeted ESLint, the structure check, and all six desktop and mobile mock browser checks. The live route redirected an unauthenticated request to staff sign-in and API readiness was UP. Signed-in verification with original data was not run.
- Frontend-only change with existing APIs. Revert this commit to roll back. No migration is needed.

## Monthly Students attendance directory (2026-11-21)

- Students now loads the academy directory across API pages and shows a monthly attendance average, scrollable registration-ID chart, and scrollable list. Name and registration-ID search, class-type filtering, and sorting operate across the full directory. The average uses the visible students with measurable attendance.
- Student details group completed expected sessions by class type and show connected time and final outcomes. Pending and unavailable records are identified separately. The rate is the share of tracked expected sessions the student joined. Returning to the directory retains the month and filters. Capped attendance reports are read in smaller date ranges rather than showing partial totals.
- Passed 79 unit tests, TypeScript, full frontend lint, and the structure check. The new flow passed two focused desktop/mobile mock checks with 208 student records, including pagination, search, chart labels, averages, sorting, drill-down and return. All 18 existing attendance, student and navigation browser checks in the regression run passed. Live API readiness and staff sign-in redirects passed. Signed-in original data and the full real-stack suite were not verified.
- Student management, invitations, and import remain available separately. The monthly routes call the existing staff APIs and require no migration. Revert this commit to roll back.

## Student directory filter placement (2026-11-21)

- Search, class type, and sort controls sit above the scrolling list inside Student directory. Their selection drives the directory, bar chart, and overall average.
- TypeScript, targeted ESLint, and the structure check passed. Desktop and mobile mock browser checks verified the controls inside the directory and their effect on attendance. Signed-in verification with original data was not run.
- Frontend-only layout change. Revert this commit to roll back.

## Excel attendance reports (2026-11-21)

- Started and verified the local project against the original Supabase database before implementing Reports. The runtime uses the existing transaction-pool override and already migrated schema.
- The staff Reports tab defaults to the current Sri Lanka month. Staff can choose up to 12 months and export one completed session, class type, or student as an Excel workbook. Each report uses the existing staff-scoped APIs, safe string and numeric cells, and private no-store responses. Successful workbook generation records the actor, academy, report kind, and period in the web server log.
- Passed 81 unit tests, TypeScript, ESLint, the structure check, and npm audit. Four desktop/mobile mock browser checks opened all three downloaded workbooks, verified month boundaries and staff-only access. The running Supabase-backed API reported UP, sign-in returned 200, and the new routes redirected signed-out requests. Signed-in downloads against original data were not run.
- Frontend-only rollout with the locked workbook dependency. Revert this commit to roll back. No migration is needed.

## Searchable report selection (2026-11-21)

- Each report uses a scrollable single-selection list with a search field. Students can be found by name or registration ID, including IDs entered without separators. The selected record remains visible in the footer while searching, and the download uses the same validated report route.
- Passed 81 unit tests, TypeScript, targeted ESLint, and the structure check. Four desktop and mobile mock browser checks covered student search, selection during filtering, all three Excel downloads, and access guards. Live API readiness and the signed-out Reports redirect passed. Signed-in original data was not tested.
- Frontend-only change. Revert this commit to roll back.

## Staff performance instrumentation (2026-11-21)

- Added the [staff performance checklist](../../testing/STAFF-PERFORMANCE.md), HTTP timing at the Next proxy and API response commit, and current Classes, Class Types, Students and Reports paths in the staff load scenario.
- Verified the Next timing header on a production build. The Testcontainers HTTP test passed for both successful and refused API requests. All 81 frontend unit tests, k6 scenario inspection, and the structure check passed. Signed-in load measurements are still pending.
- No database or permission changes. Revert this commit to remove the headers and scenario updates.

## Signed-in synthetic performance baseline (2026-11-21)

- Used an isolated Docker PostgreSQL and production build with 150 synthetic students, 28 completed sessions and four k6 staff users. All 60 requests passed. Page p95 was 392 ms for Classes, 412 ms for Class Types, and 658 ms for Students. See the [checklist](../../testing/STAFF-PERFORMANCE.md) for samples, other routes and limitations.
- The harness supports an alternate jar, Windows Docker Desktop and a configurable Python command. The first run collected browser-facing HTTP and SQL data. Its API access-log parser could not start under the default Windows `python3` alias. A later comparison should set `LUMA_LOAD_PYTHON`.
- The fixture never used the original database or Zoom credentials. No application migration or deployment was made. Revert this test-tooling commit to restore the previous fixture.

## Batched attendance totals (2026-11-21)

- Monthly and recent completed totals now read linked Zoom evidence in batches of up to 16 authorized classes. Identity lookup is shared within each batch. Host exclusion, registration matching, report precedence and expected-roster filtering remain class-specific.
- Passed 26 PostgreSQL integration tests across attendance insights, provisional attendance, Zoom participants and Zoom reports. The new parity case combines report evidence, host and registrant roles across two sessions. The before/after k6 comparison is tracked separately.
- No API contract or migration change. Revert this commit to restore per-class reads.

## Batched Classes load comparison (2026-11-21)

- Repeated the 60-request synthetic benchmark with backend `46a4a6a` and the same frontend build. There were zero failures. Classes page p95 dropped from 392 ms to 185 ms and Class Types from 412 ms to 131 ms. The report-participant SQL executed 264 times instead of 680. The staff performance checklist records the sample count and limitations.
- Verified local behavior only. No live service or database configuration changed. This commit records evidence and has no runtime rollout step.

## Batched detailed attendance reads (2026-11-21)

- The attendance dashboard and student detail now batch named rosters, Zoom evidence and final records over authorized classes. Students enrolled in overlapping batches still count once per session, and the existing batch codes and filtering remain intact. The existing range and export contracts still apply.
- Passed 27 PostgreSQL integration tests across insights, provisional attendance, Zoom reports and participants, and enrollment authorization. A separate repeat benchmark will measure the Students improvement on the same fixture.
- No migration. Revert this commit to restore the per-class detail reads.

## Streamed staff attendance pages (2026-11-21)

- Classes, Class Types and Students stream the month controls and page layout before their heavier reads. On Classes the tracked Zoom links and class types have independent loading boundaries. Both columns share the same monthly requests. Errors show a Retry action inline and retry the current route without a browser reload.
- The production Next build, ESLint and TypeScript passed. All 14 desktop and mobile Chromium checks passed, including a held Zoom response and the failure and retry flow. A first browser run could not start because the Playwright Chromium revision was missing. Installed that revision for the successful rerun.
- Frontend-only slice. No hosted timing or visual check on original data was run. Revert this commit to restore the earlier rendering strategy.

## Completed attendance projection and compact monthly API (2026-11-21)

- V31 adds an opt-in projection of reconciled Zoom visits. It stores counts and duration per matched student, validates source and academy identity revisions, and falls back to source reads when stale. Roster membership and official attendance remain live reads. The worker uses bounded leases and compare-and-publish. Erasure removes projected student rows transactionally.
- Added the staff-only monthly directory API with academy scope, private no-store responses, full directory coverage and compact class-type totals. Updated the OpenAPI contract, authorization matrix and architecture. The frontend integration is a separate slice.
- Passed 19 PostgreSQL integration tests for compact directory access and participation, projection parity, concurrent claims, stale and empty results, corrections and erasure. No original Supabase benchmark or migration was performed. Apply the additive V31 in a reviewed rollout, then enable `LUMA_ATTENDANCE_PROJECTION_ENABLED` after the hosted gate. Set it to false to return to source reads and leave the schema in place on rollback.

## On-demand report options (2026-11-21)

- Reports opens on three report type cards and the month controls. The selected type loads its searchable picker without fetching unrelated options. The download route and date rules stay the same.
- Production build, 81 frontend unit tests and desktop/mobile browser checks passed. The browser checks opened and verified each of the three generated Excel files, including a student selected by registration ID without separators. A first run exposed a shadowed variable in the mock for the new student fixture. Fixed it and passed the rerun.
- Frontend-only. Revert this commit to restore the three eagerly loaded pickers. Original Supabase exports were not tested.

## Compact Students month view (2026-11-21)

- The directory makes one request for student identities and class-type totals. Full-result search, sorting and averages stay immediate in the browser, and offscreen rows and chart bars defer layout. A 404 from an older backend uses the previous read path.
- The backend scope, no-store, unenrolled student and attendance parity tests passed. The production build and signed-in k6 run passed. With all 28 projections ready, Students page p95 was 109 ms versus 658 ms at baseline, and its compact API p95 was 65 ms. The frontend made eight compact calls and no attendance-dashboard calls during this run.
- The monthly API uses V31 on the current backend. Revert this frontend integration to restore the prior reads. No hosted configuration or data changed.

## Authentication round trip and debug log safety (2026-11-21)

- Silent renewal leaves one current identity check in the protected render. Backend permission, role and revocation checks remain in the request path. Browser cache settings stay at their previous lifetimes.
- The first full Maven suite detected that Spring MVC body debugging could print sensitive request or response fields when debug was enabled. Kept MVC body-processor logs at INFO. All 403 Maven tests passed on rerun with the same environment. The Next HTTP session smoke passed and asserted one identity call during renewal. Two browser projects verified private no-store behavior, logout and switching to a student account.
- No migration or credentials change for this slice. The benchmark recorded `/auth/me` at p95 58 ms. Revert without re-enabling MVC request-body debug logging.

## Staff performance regression gates (2026-11-21)

- The pull-request load workflow runs the synthetic signed-in staff scenario with warmed projections. Frontend checks include the signed-in Lighthouse budgets. The Lighthouse harness uses loopback mock data and a temporary tool install. No student data or browser logs are uploaded by the Lighthouse harness.
- The exact targeted k6 scenario passed all 60 checks with zero failures. The local Edge Lighthouse run passed all nine route and metric assertions using the median of three runs per route. The [performance checklist](../../testing/STAFF-PERFORMANCE.md) records numbers, budgets, the hosted verification gate and rollback.
- GitHub workflows have yet to run remotely. No hosted metrics, database settings, or deployments were changed. Revert the workflow and harness commit to remove the new gates.

## Targeted live staff polling (2026-11-21)

- The staff dashboard polls only live counts and sample history while the tab is visible. Session detail polls attendance, Zoom state and enabled engagement. Both retain pause, recover history on arrival and coordinate a refresh when a class ends. The BFF endpoints authorize and scope each read and return private no-store responses.
- Passed the production build, full ESLint, 81 frontend unit tests, the 113-endpoint contract check and six final desktop/mobile browser checks. The browser cases verify timeline recovery, pause, targeted data requests, finalisation, and removal of an ended run while another class stays active.
- The ten local slices in the [performance checklist](../../testing/STAFF-PERFORMANCE.md) are complete. Original Supabase and hosted performance checks remain open. This slice is frontend-only and can be rolled back independently by reverting the commit.

## Quick return to staff sidebar tabs (2026-11-21)

- Classes, Class Types, Students and Reports reuse recent page data in the Next.js client router for up to 90 seconds. Leaving through the sidebar remembers the exact selection URL in the signed-in layout. Links to remembered query variants prefetch that route. The prefetch lifetime is also 90 seconds. No HTTP, CDN or persistent browser data caching was added.
- The browser regression reproduced an extra Students request after 45 seconds on the prior build. The new build reused the default page and the selected month after 45 seconds and fetched again after expiry. All 12 focused desktop and mobile checks, 81 unit tests, production build, ESLint and the structure check passed. Browser checks used synthetic data. Sign-out and account switching still kept student data private.
- Restarted the frontend on port 3000 and verified HTTP 200 on sign-in. Backend readiness on port 8080 was UP. Signed-in navigation against original Supabase was not timed. Revert the commit, rebuild and restart the frontend to restore the earlier behavior. No migration is needed.
