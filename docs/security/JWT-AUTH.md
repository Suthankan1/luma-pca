# Authentication and token security — implementation specification

**Architecture decision:** first-party sign-in for both staff and students. **Status:** accounts, invitations, password sign-in, RS256 access tokens, key rotation and lockout (001A–001C), rotating refresh sessions (001D) and email-and-password sign-in for every role are implemented. The TOTP MFA of 001F/001G was removed at the owner's request on 2026-11-20 (see below). Password recovery, rate limiting by client address, a session list and audit are planned.

**As built (001C):** access tokens live 10 minutes by default (1–15 allowed) and carry `iss`, `aud`, `sub`, `jti`, `iat`, `nbf`, `exp`, `academy_id`, `auth_version`; key ids are RFC 7638 thumbprints. Every request re-reads the account, so disabling it or changing its password rejects earlier tokens and roles are always current. Five failed sign-ins lock an account for 15 minutes; attempts are counted before the password is checked, and a locked account gets the same 401 as a wrong password. The API disables Spring CSRF because it never authenticates from cookies; CSRF protection belongs to the Next.js BFF.

**As built (001D):** a sign-in creates a refresh session with one current opaque token (256-bit random, stored as a SHA-256 digest; plain SHA-256 rather than HMAC because the token is not guessable). Refresh exchanges the token for a new pair; a token unused for 7 days expires, and a session ends after 30 days regardless. Each operation locks the session row, so concurrent refreshes are serialised: the first rotates, any later presentation of the same token revokes the whole session. Access tokens carry the session id (`sid`) and are rejected as soon as the session is revoked, so logout takes effect immediately rather than after the access token expires. A password change or disablement ends sessions at their next use. All refresh failures return one identical 401; logout always returns 204.

**As built (MFA removed, 2026-11-20, owner decision):** there is no second factor. `POST /auth/login` returns session tokens for any active account, staff or student, whose email and password are correct; the lockout (five failures, 15 minutes), per-visitor rate limit and identical 401 for every refusal are unchanged. The `/auth/mfa/enroll`, `/auth/mfa/verify` and `/auth/mfa/reset` endpoints, the BFF's `/sign-in/mfa` pages and `mfa-*` session actions, the `luma-mfa`/`luma-recovery` cookies and `LUMA_AUTH_MFA_ENCRYPTION_KEY` are gone. Bearer authentication and refresh no longer look at whether a session completed MFA, so an account given the staff role keeps its current session. The V6 tables (`account_totp`, `account_recovery_codes`, `mfa_challenges`) and the `refresh_sessions.mfa_verified` column stay in the schema but are no longer read; existing rows are inert and student erasure still deletes them. Dropping them would be a new, irreversible migration. Risk accepted by the owner: a leaked or guessed staff password alone now opens that staff portal.

*Superseded (001F, MFA; widened in FIX-3 to every staff role):* every staff account needed a TOTP authenticator code (with one-time recovery codes) after the password step.

**As built (001I, activation):** invitation links have the form `https://<web origin>/activate#token=<code>`. The code is in the URL fragment, which browsers never send to servers or in Referer headers; the page copies it into the form and removes it from the address bar. The person may also paste the code or the whole link. The form posts to the BFF route `/api/session/activate`, which applies the same origin check as sign-in, rejects a missing code or mismatched passwords before calling the backend, and redirects with the code kept in the fragment when the person must retry. Activation does not sign the person in; they sign in afterwards with their email and new password.

## Data and identity
- A single `accounts` table with UUID, academy ID, normalized unique email (or approved alternative student identifier), password hash, disabled/locked status, auth version, timestamps; distinct `students` record joins to account where applicable.
- Two roles (owner decision 2026-11-20): `STAFF` does every staff action within its academy; `STUDENT` may only read own records. An account may hold both. The five earlier staff roles and batch-scoped assignments were retired by migration V30; see `docs/security/AUTHORIZATION-MATRIX.md`.
- Admin-approved invitation/roster import is recommended over unrestricted self-registration. No second factor (removed 2026-11-20; strong unique staff passwords matter more as a result). Rate-limit login, verify account recovery and invalidate sessions on credential reset.

## Access JWT
- Asymmetric **RS256** (or ES256 following an ADR) signing; private keys managed as secrets, never checked into Git.
- Header `alg=RS256`, `kid`; required claims `iss`, `aud`, `sub` (account UUID), `iat`, `nbf`, `exp`, unique `jti`; optional `academy_id`, `auth_version`. Tokens are signed, not encrypted; do not include PII, passwords or whole enrollment/role lists.
- Suggested initial lifetime **5–10 minutes** (finalize policy). Validate algorithm allowlist, signature via JWKS key rotation, issuer, audience, clock skew and account revocation/`auth_version` where applicable.
- Spring Security **resource server** protects private REST endpoints. Do not trust UI route visibility or stale role claims: evaluate current persisted scoped grants and resource ownership per operation.

## Refresh sessions and browser BFF
- Refresh token is a strong **opaque random secret**, not a long-lived signed JWT; persist only a keyed hash (prefer HMAC SHA-256 + server secret) and session/family metadata.
- Every use rotates token atomically; a reused ancestor revokes whole family and audits potential compromise. Support logout, password-reset revocation, session list and logout-all.
- Browser receives only **Secure, HttpOnly, SameSite=Lax or Strict** cookies via Next.js BFF and a server-side CSRF strategy for state-changing requests. Prefer keeping access JWT server side; do not store it in localStorage/sessionStorage, embed it in HTML or pass it to client JS.
- BFF handles login/refresh/logout and proxies authenticated backend calls. Verify Origin/CSRF on mutations, set restrictive CORS, no permissive wildcard credential access. Backend must authorize every route separately.

## Browser sign-in
- `/api/session/login` (Origin and Sec-Fetch-Site checked) sends email and password to `POST /api/v1/auth/login`. The optional student registration-ID flow uses `/api/session/student-login`. When a portal is selected, the BFF checks the API-reported roles before setting cookies. Credentials for a different portal cause the newly issued session to be revoked, and the visitor returns to the chosen sign-in form. The shared sign-in page continues to use the account's default portal.
- Successful sign-out revokes the refresh session, expires the browser cookies and redirects to the landing page. Protected tabs in the same browser return there when they receive the sign-out event. If the revocation request fails, the browser shows a retry option.

## Key management
- Do not create keys in documentation or config. Generate per-environment keys in managed secrets/KMS. Publish public JWKS only. Rotate without rejecting still-valid previous-kid access tokens; protect private-key access and audit rotations.
- Zoom S2S OAuth `account_id/client_id/client_secret` is a **different trust domain**: only backend holds it; staff JWT does not grant OAuth rights to Zoom.

## Abuse and privacy protections
- Password hashing Argon2id (preferred) or calibrated BCrypt; never homebrew or plaintext.
- Rate limit login/reset and reaction endpoints; anti-enumeration errors; no tokens or private data in logs/traces; audit sensitive actions.
- Account deletion/retention subject to academy policy and data-protection guidance. Implement positive and negative security tests with student ownership and batch cross-scope IDOR.

## Definition of done for auth release
Test invite expiry, hash verification, login failures, disabled users, refresh rotation and replay, logout-all, key rotation/unknown key, exp/iss/aud mismatch, CSRF, BFF cookie flags, staff batch-scoping, student self-only data, audit trails and no token leakage. No production deployment before these pass.

## Staying signed in (SESSION-2, 2026-11-18)

Reported problem: people were "signed out" after about 10 minutes. The access token (and the web app's access cookie)
lasts 10 minutes by design, and nothing renewed it, so every page after that sent people to the sign-in page.

- **Silent renewal:** the web app's proxy (`frontend/src/proxy.ts`) runs before every page. When the access cookie has
  expired but the refresh cookie is still there, it renews the session with the API and continues the same request with
  the new cookies. People stay signed in for as long as the refresh session lasts (refresh token 7 days of idleness,
  session at most 30 days): always at least the requested 2 hours.
- **Parallel page loads:** a page and its prefetches share one renewal in the proxy. Across server instances, the API
  accepts a just-used refresh token for 30 seconds (`RefreshSessions.CONCURRENT_GRACE`) and issues a new token in the same
  session (V28 allows more than one current token per session). Presenting a used token after 30 seconds is still treated
  as theft and revokes the whole session.
- **Sign out is immediate:** it revokes the session in the API and clears both cookies; the API checks the session on
  every request, so even an unexpired access token stops working at once (`RefreshSessionHttpIntegrationTest`).
- Access tokens stay short (10 minutes, at most 15), as this document requires.
