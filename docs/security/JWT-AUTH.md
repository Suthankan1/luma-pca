# Authentication and token security — implementation specification

**Architecture decision:** first-party sign-in for both staff and students. **Status:** accounts, invitations, password sign-in, RS256 access tokens, key rotation and lockout (001A–001C), rotating refresh sessions (001D), and backend TOTP MFA (001F) are implemented. Browser MFA, password recovery, rate limiting by client address, a session list and audit are planned.

**As built (001C):** access tokens live 10 minutes by default (1–15 allowed) and carry `iss`, `aud`, `sub`, `jti`, `iat`, `nbf`, `exp`, `academy_id`, `auth_version`; key ids are RFC 7638 thumbprints. Every request re-reads the account, so disabling it or changing its password rejects earlier tokens and roles are always current. Five failed sign-ins lock an account for 15 minutes; attempts are counted before the password is checked, and a locked account gets the same 401 as a wrong password. The API disables Spring CSRF because it never authenticates from cookies; CSRF protection belongs to the Next.js BFF.

**As built (001D):** a sign-in creates a refresh session with one current opaque token (256-bit random, stored as a SHA-256 digest; plain SHA-256 rather than HMAC because the token is not guessable). Refresh exchanges the token for a new pair; a token unused for 7 days expires, and a session ends after 30 days regardless. Each operation locks the session row, so concurrent refreshes are serialised: the first rotates, any later presentation of the same token revokes the whole session. Access tokens carry the session id (`sid`) and are rejected as soon as the session is revoked, so logout takes effect immediately rather than after the access token expires. A password change or disablement ends sessions at their next use. All refresh failures return one identical 401; logout always returns 204.

**As built (001F, MFA):** owner and administrator accounts, and any account that has an authenticator, get no tokens from the password step. `POST /auth/login` returns `{mfaRequired, mfaToken, mfaExpiresIn, mfaEnrolled}`; the challenge lives 5 minutes and is single-use. First time: `POST /auth/mfa/enroll` returns a Base32 secret and `otpauth://` URI. `POST /auth/mfa/verify` accepts a 6-digit code (RFC 6238, SHA-1, 30-second steps, one step of drift either way) and starts the session. Completing enrolment returns ten single-use recovery codes once. After enrolment, a recovery code can replace the authenticator code. Verification rejects replayed steps and used recovery codes. The default lockout is five wrong codes for 15 minutes. An account row lock serializes attempts. Successful verification and session creation commit together. Unusable challenges and rejected codes return the same 401. Authenticator secrets are encrypted with AES-256-GCM under `LUMA_AUTH_MFA_ENCRYPTION_KEY` and bound to the account; recovery codes and challenges are stored as SHA-256 digests. Sessions record whether MFA was completed. An account promoted to owner or administrator must sign in again and complete MFA. Its earlier password-only sessions fail bearer authentication and refresh. `POST /auth/mfa/reset` requires a live bearer session and the password. An enrolled account must use an MFA-verified session. Reset removes the authenticator, recovery codes and pending challenges and ends all sessions in one transaction. The earlier `LUMA_AUTH_ALLOW_ELEVATED_LOGIN_WITHOUT_MFA` override no longer exists. Trust model: whoever first signs in to a new owner/admin account with its password enrols the authenticator.

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
