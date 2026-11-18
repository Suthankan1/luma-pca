-- A browser opening several pages at once renews its session in parallel with the same refresh token. Within a short
-- grace period (RefreshSessions.CONCURRENT_GRACE) each of those requests now gets its own new token in the same session,
-- so a session may briefly have more than one unused token. Theft detection, revocation and sign-out stay per session:
-- a used token presented after the grace period still revokes the whole session, and sign-out revokes it at once.
DROP INDEX IF EXISTS uq_refresh_tokens_current;
