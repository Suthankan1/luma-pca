-- Slice 001J: self-service password reset by email. Like invitations, only a SHA-256
-- digest of the 256-bit random token is stored; the token exists only in the email.
CREATE TABLE password_resets (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  token_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  CONSTRAINT uq_password_resets_token_hash UNIQUE (token_hash),
  CONSTRAINT ck_password_resets_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_password_resets_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_password_resets_single_outcome CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
-- At most one usable reset per account; a new request revokes the earlier one.
CREATE UNIQUE INDEX uq_password_resets_open ON password_resets (account_id)
  WHERE consumed_at IS NULL AND revoked_at IS NULL;
-- Requests per account per hour are counted for rate limiting.
CREATE INDEX ix_password_resets_account_created ON password_resets (account_id, created_at);
