-- Slice 001F: time-based one-time-password MFA (RFC 6238) for elevated accounts.

-- Whether this sign-in passed a second factor. Owner/admin authority is only
-- honoured on sessions where it did.
ALTER TABLE refresh_sessions ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE account_totp (
  account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  -- AES-256-GCM (12-byte nonce, then ciphertext and tag), bound to the account id.
  -- The authenticator secret must be recoverable, so it is encrypted, not hashed.
  secret_ciphertext BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  -- NULL until the first correct code proves the authenticator was set up.
  confirmed_at TIMESTAMPTZ,
  -- Highest 30-second step already accepted; a code can never be replayed.
  last_used_step BIGINT,
  failed_count INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT ck_account_totp_failed_count CHECK (failed_count >= 0)
);

CREATE TABLE account_recovery_codes (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  CONSTRAINT uq_account_recovery_codes UNIQUE (account_id, code_hash),
  CONSTRAINT ck_account_recovery_codes_hash CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

-- Proof that the password step succeeded, exchanged for a session once the second
-- factor is verified. Short-lived and single-use.
CREATE TABLE mfa_challenges (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  token_hash VARCHAR(64) NOT NULL,
  auth_version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  CONSTRAINT uq_mfa_challenges_hash UNIQUE (token_hash),
  CONSTRAINT ck_mfa_challenges_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_mfa_challenges_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_mfa_challenges_account ON mfa_challenges (account_id);
