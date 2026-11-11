-- Slice 001B: single-use, expiring activation invitations for pending accounts.
-- Only a SHA-256 digest of the 256-bit random token is stored; the token itself
-- is shown once to the issuer and never persisted.
CREATE TABLE account_invitations (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  token_hash VARCHAR(64) NOT NULL,
  created_by UUID REFERENCES accounts(id),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  CONSTRAINT uq_account_invitations_token_hash UNIQUE (token_hash),
  CONSTRAINT ck_account_invitations_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_account_invitations_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_account_invitations_single_outcome CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
-- At most one usable invitation per account; issuing a new one revokes the old.
CREATE UNIQUE INDEX uq_account_invitations_open ON account_invitations (account_id)
  WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_account_invitations_account ON account_invitations (account_id);
