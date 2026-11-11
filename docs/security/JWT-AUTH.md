# Authentication and token security — implementation specification

**Architecture decision:** first-party sign-in for both staff and students. **Status:** accounts, invitations, password sign-in, RS256 access tokens, key rotation and lockout are implemented (slices 001A–001C); refresh sessions (001D) are implemented too; MFA, recovery, rate limiting by client address, a session list and audit are not. No production logins exist.

**As built (001C):** access tokens live 10 minutes by default (1–15 allowed) and carry `iss`, `aud`, `sub`, `jti`, `iat`, `nbf`, `exp`, `academy_id`, `auth_version`; key ids are RFC 7638 thumbprints. Every request re-reads the account, so disabling it or changing its password rejects earlier tokens and roles are always current. Five failed sign-ins lock an account for 15 minutes; attempts are counted before the password is checked, and a locked account gets the same 401 as a wrong password. Owner/admin sign-in returns 403 until MFA exists unless `LUMA_AUTH_ALLOW_ELEVATED_LOGIN_WITHOUT_MFA=true` (development only). The API disables Spring CSRF because it never authenticates from cookies; CSRF protection belongs to the Next.js BFF.

**As built (001D):** a sign-in creates a refresh session with one current opaque token (256-bit random, stored as a SHA-256 digest; plain SHA-256 rather than HMAC because the token is not guessable). Refresh exchanges the token for a new pair; a token unused for 7 days expires, and a session ends after 30 days regardless. Each operation locks the session row, so concurrent refreshes are serialised: the first rotates, any later presentation of the same token revokes the whole session. Access tokens carry the session id (`sid`) and are rejected as soon as the session is revoked, so logout takes effect immediately rather than after the access token expires. A password change or disablement ends sessions at their next use. All refresh failures return one identical 401; logout always returns 204.

## Data and identity
- A single `accounts` table with UUID, academy ID, normalized unique email (or approved alternative student identifier), password hash, disabled/locked status, auth version, timestamps; distinct `students` record joins to account where applicable.
- Staff roles are independent assignments (`ACADEMY_OWNER`, `ACADEMY_ADMIN`, `INSTRUCTOR`, `ATTENDANCE_OFFICER`, optional `ANALYST`) with academy/course/batch scope. `STUDENT` may only read own records. Users can have multiple scopes.
- Admin-approved invitation/roster import is recommended over unrestricted self-registration. MFA for elevated staff and high-risk changes. Rate-limit login, verify account recovery and invalidate sessions on credential reset.

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

## Key management
- Do not create keys in documentation or config. Generate per-environment keys in managed secrets/KMS. Publish public JWKS only. Rotate without rejecting still-valid previous-kid access tokens; protect private-key access and audit rotations.
- Zoom S2S OAuth `account_id/client_id/client_secret` is a **different trust domain**: only backend holds it; staff JWT does not grant OAuth rights to Zoom.

## Abuse and privacy protections
- Password hashing Argon2id (preferred) or calibrated BCrypt; never homebrew or plaintext.
- Rate limit login/reset and reaction endpoints; anti-enumeration errors; no tokens or private data in logs/traces; audit sensitive actions.
- Account deletion/retention subject to academy policy and data-protection guidance. Implement positive and negative security tests with student ownership and batch cross-scope IDOR.

## Definition of done for auth release
Test invite expiry, hash verification, login failures, disabled users, refresh rotation and replay, logout-all, key rotation/unknown key, exp/iss/aud mismatch, CSRF, BFF cookie flags, staff batch-scoping, student self-only data, admin MFA, audit trails and no token leakage. No production deployment before these pass.
