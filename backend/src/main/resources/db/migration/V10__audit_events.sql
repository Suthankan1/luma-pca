-- Append-only record of sensitive administrative actions. Details never hold
-- passwords, tokens or personal data; targets are referenced by id.
CREATE TABLE audit_events (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  -- No foreign key: the history must survive the actor's account.
  actor_account_id UUID,
  action VARCHAR(60) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id UUID,
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_audit_events_action CHECK (action ~ '^[A-Z][A-Z_]*$')
);
CREATE INDEX ix_audit_events_academy_time ON audit_events (academy_id, occurred_at DESC);

CREATE FUNCTION audit_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_events is append-only';
END;
$$;
CREATE TRIGGER audit_events_no_update_or_delete BEFORE UPDATE OR DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();
