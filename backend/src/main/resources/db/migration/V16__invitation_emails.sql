-- Slice STU-3: durable queue of invitation emails for bulk invitations.
-- A row asks for one pending account to be emailed an activation link. The token is
-- created by the sender at sending time (and stored only as a digest, in
-- account_invitations), never here. Delivery is at least once: a worker that dies after
-- sending leaves the row due again once its lease ends, and a resend replaces the link.
CREATE TABLE invitation_emails (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  requested_by UUID NOT NULL REFERENCES accounts(id),
  status VARCHAR(16) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  last_error VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  CONSTRAINT ck_invitation_emails_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_invitation_emails_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_invitation_emails_finished CHECK ((status = 'QUEUED') = (finished_at IS NULL))
);
-- At most one waiting email per account; queueing again while one waits does nothing.
CREATE UNIQUE INDEX uq_invitation_emails_queued ON invitation_emails (account_id) WHERE status = 'QUEUED';
CREATE INDEX ix_invitation_emails_due ON invitation_emails (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX ix_invitation_emails_academy ON invitation_emails (academy_id, created_at);
