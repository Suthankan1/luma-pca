# Authentication and token security — implementation specification

**Architecture decision:** first-party sign-in for both staff and students. **Status:** accepted design, not implemented in starter. No production logins exist.

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
