-- Slice 003B: durable receipts of verified Zoom webhook deliveries (the inbox).
-- A receipt is stored and committed before Zoom gets a 2xx. Payloads may contain
-- participant names and emails: never log them; retention policy is an open
-- academy question.
CREATE TABLE zoom_webhook_receipts (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  event_type VARCHAR(100) NOT NULL,
  -- Zoom's event time in epoch milliseconds, as sent.
  event_ts BIGINT NOT NULL,
  -- Zoom documents no event identifier, so a redelivery is recognised by the
  -- SHA-256 of the exact request body. Business processing must stay idempotent too.
  body_sha256 VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  CONSTRAINT uq_zoom_webhook_receipts_body UNIQUE (academy_id, body_sha256),
  CONSTRAINT ck_zoom_webhook_receipts_sha CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_zoom_webhook_receipts_status CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);
CREATE INDEX ix_zoom_webhook_receipts_pending ON zoom_webhook_receipts (processing_status, received_at);
