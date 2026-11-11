-- Slice 001D: refresh sessions. A session is one sign-in (a token "family"); each
-- refresh replaces its single current token. Only SHA-256 digests of the 256-bit
-- random tokens are stored.
CREATE TABLE refresh_sessions (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  -- accounts.auth_version at sign-in; a later password change or disablement ends the session.
  auth_version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  -- Absolute limit: refreshing never extends a session beyond this.
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  revoke_reason VARCHAR(30),
  CONSTRAINT ck_refresh_sessions_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_refresh_sessions_revocation CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
  CONSTRAINT ck_refresh_sessions_reason CHECK (revoke_reason IS NULL OR revoke_reason IN (
    'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'ACCOUNT_CHANGED'))
);
CREATE INDEX ix_refresh_sessions_account_active ON refresh_sessions (account_id) WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES refresh_sessions(id) ON DELETE CASCADE,
  token_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  -- Set when the token is exchanged. Presenting a used token again revokes the session.
  used_at TIMESTAMPTZ,
  CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
  CONSTRAINT ck_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at)
);
-- A session has at most one token that has not been exchanged yet.
CREATE UNIQUE INDEX uq_refresh_tokens_current ON refresh_tokens (session_id) WHERE used_at IS NULL;
CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);
