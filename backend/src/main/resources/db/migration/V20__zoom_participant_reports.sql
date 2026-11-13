-- Slice 004B: Zoom's own participant report for each webinar occurrence, fetched after the
-- webinar ends (GET /past_webinars/{uuid}/participants). Once an occurrence has its report,
-- attendance uses it instead of the live webhook events.

-- One row per occurrence: whether its report has been fetched, and retry state.
CREATE TABLE zoom_report_fetches (
  academy_id UUID NOT NULL REFERENCES academies(id),
  occurrence_uuid VARCHAR(100) NOT NULL,
  -- FETCHED = the rows below are Zoom's report; FAILED = the last attempt failed (retried later).
  status VARCHAR(20) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  -- Our own fixed text or Zoom's error code; never response content.
  last_error VARCHAR(300),
  fetched_at TIMESTAMPTZ,
  join_sessions INTEGER,
  CONSTRAINT pk_zoom_report_fetches PRIMARY KEY (academy_id, occurrence_uuid),
  CONSTRAINT ck_zoom_report_fetches_status CHECK (status IN ('FETCHED', 'FAILED')),
  CONSTRAINT ck_zoom_report_fetches_fetched CHECK ((status = 'FETCHED') = (fetched_at IS NOT NULL AND join_sessions IS NOT NULL))
);
CREATE INDEX ix_zoom_report_fetches_due ON zoom_report_fetches (next_attempt_at) WHERE status = 'FAILED';

-- One row per join session in Zoom's report. Only what attendance needs: no IP addresses,
-- devices or locations. Replaced as a whole when the report is fetched again.
CREATE TABLE zoom_report_participants (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL,
  occurrence_uuid VARCHAR(100) NOT NULL,
  -- Lower-cased; NULL when Zoom reports none.
  email VARCHAR(254),
  display_name VARCHAR(200),
  registrant_id VARCHAR(100),
  joined_at TIMESTAMPTZ NOT NULL,
  left_at TIMESTAMPTZ NOT NULL,
  duration_seconds INTEGER NOT NULL,
  CONSTRAINT fk_zoom_report_participants_fetch FOREIGN KEY (academy_id, occurrence_uuid)
    REFERENCES zoom_report_fetches (academy_id, occurrence_uuid),
  CONSTRAINT ck_zoom_report_participants_email CHECK (email IS NULL OR (email <> '' AND email = lower(btrim(email)))),
  CONSTRAINT ck_zoom_report_participants_times CHECK (left_at >= joined_at AND duration_seconds >= 0)
);
CREATE INDEX ix_zoom_report_participants_occurrence ON zoom_report_participants (academy_id, occurrence_uuid);
