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
