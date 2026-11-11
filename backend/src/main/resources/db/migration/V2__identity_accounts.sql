-- Slice 001A: persistent sign-in accounts and academy-level role grants.
-- Course/batch-scoped staff assignments, refresh sessions, invitations and
-- recovery tokens arrive in their own migrations.
CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  -- Login identifier. Globally unique so sign-in by email is unambiguous.
  email VARCHAR(254) NOT NULL,
  -- Self-describing password hash (Argon2id). NULL until the account is activated.
  password_hash VARCHAR(255),
  status VARCHAR(30) NOT NULL,
  -- Incremented on password change or disablement so issued tokens can be rejected.
  auth_version INTEGER NOT NULL DEFAULT 0,
  failed_login_count INTEGER NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ,
  password_changed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_accounts_email UNIQUE (email),
  CONSTRAINT ck_accounts_email_normalized CHECK (email <> '' AND email = lower(btrim(email))),
  CONSTRAINT ck_accounts_status CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED')),
  CONSTRAINT ck_accounts_active_has_password CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL),
  CONSTRAINT ck_accounts_counters CHECK (auth_version >= 0 AND failed_login_count >= 0)
);
CREATE INDEX ix_accounts_academy ON accounts (academy_id);

CREATE TABLE account_roles (
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  role VARCHAR(30) NOT NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_account_roles PRIMARY KEY (account_id, role),
  CONSTRAINT ck_account_roles_role CHECK (role IN (
    'ACADEMY_OWNER', 'ACADEMY_ADMIN', 'INSTRUCTOR', 'ATTENDANCE_OFFICER', 'ANALYST', 'STUDENT'))
);
