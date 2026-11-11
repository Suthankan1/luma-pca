-- Slice 003C: processing state for webhook receipts (retry, backoff, dead letter)
-- and the per-occurrence event log that attendance will read.
ALTER TABLE zoom_webhook_receipts
  DROP CONSTRAINT ck_zoom_webhook_receipts_status,
  ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN last_error VARCHAR(300),
  ADD COLUMN processed_at TIMESTAMPTZ,
  -- RECEIVED = waiting (first try or retry), PROCESSED = done, DEAD = needs an operator.
  ADD CONSTRAINT ck_zoom_webhook_receipts_status CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'DEAD')),
  ADD CONSTRAINT ck_zoom_webhook_receipts_attempts CHECK (attempts >= 0),
  ADD CONSTRAINT ck_zoom_webhook_receipts_processed CHECK ((processing_status = 'PROCESSED') = (processed_at IS NOT NULL));
DROP INDEX ix_zoom_webhook_receipts_pending;
CREATE INDEX ix_zoom_webhook_receipts_due ON zoom_webhook_receipts (next_attempt_at) WHERE processing_status = 'RECEIVED';
CREATE INDEX ix_zoom_webhook_receipts_dead ON zoom_webhook_receipts (academy_id, received_at) WHERE processing_status = 'DEAD';

-- One row per processed receipt, so reprocessing can never duplicate it. Ordered by
-- Zoom's event time, not arrival time, because deliveries can arrive out of order.
-- Participant details stay in the receipt until identity mapping is proven (003D).
CREATE TABLE zoom_occurrence_events (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  receipt_id UUID NOT NULL REFERENCES zoom_webhook_receipts(id),
  webinar_id VARCHAR(40) NOT NULL,
  occurrence_uuid VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  event_ts BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_zoom_occurrence_events_receipt UNIQUE (receipt_id)
);
CREATE INDEX ix_zoom_occurrence_events_occurrence ON zoom_occurrence_events (academy_id, occurrence_uuid, event_ts);
