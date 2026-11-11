# ADR-0002 — First-party JWT access tokens plus opaque refresh tokens
**Status:** Accepted in architecture; not implemented · 2026-11-11
**Decision:** Accounts authenticate with email/invitation flow and Argon2id or calibrated BCrypt. API accepts short-lived RS256 signed access JWT (`iss`,`aud`,`sub`,`iat`,`nbf`,`exp`,`jti`, `kid`) with rotation via JWKS. Session refresh uses cryptographically random **opaque**, server-hashed, rotated refresh tokens, family-reuse detection and revocation. Next.js BFF stores the browser session in Secure, HttpOnly SameSite cookies with CSRF protection. The API is protected by Spring Security role+scope checks; permissions come from persisted assignments rather than trusting broad token roles alone.
**Clarification:** JWT is not an encrypted database, not a secure alternative to scoped authorization, and is separate from Zoom's Server-to-Server OAuth.
**Consequences:** Key management, revocation, account recovery, audit, brute-force defense and CSRF tests are required before production users.
See [JWT-AUTH.md](../../security/JWT-AUTH.md).
