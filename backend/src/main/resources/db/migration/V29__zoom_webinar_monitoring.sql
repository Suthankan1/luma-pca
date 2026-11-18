-- Academy decision 2026-11-18: Zoom owns webinar schedules. Staff give LUMA only a webinar ID or link to monitor; every
-- actual run Zoom starts (webinar.started, or the first event of a run whose start was missed) becomes its own attendance
-- run keyed by the occurrence UUID, whatever the schedule says. Additive: existing classes, links and history stay.

-- A webinar LUMA monitors. Title and schedule are a read-only cache of Zoom (Get a Webinar), refreshed from Zoom; LUMA never
-- edits them and never writes to Zoom. "Stop monitoring" only clears active.
CREATE TABLE zoom_webinar_monitors (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  webinar_id VARCHAR(20) NOT NULL,
  -- Zoom's host user ID; participants with this ID are the host, not students.
  host_id VARCHAR(64),
  topic VARCHAR(300),
  time_zone VARCHAR(64),
  -- Zoom's own schedule, display only: NULL when Zoom gives none (never invented).
  scheduled_start_at TIMESTAMPTZ,
  duration_minutes INTEGER,
  recurring BOOLEAN NOT NULL DEFAULT FALSE,
  -- Zoom's occurrences as returned (occurrence_id, start_time, duration, status), display only.
  occurrences JSONB NOT NULL DEFAULT '[]'::jsonb,
  sync_status VARCHAR(20) NOT NULL,
  synced_at TIMESTAMPTZ,
  -- Our own fixed wording or Zoom's error code; never response content.
  sync_error VARCHAR(300),
  -- The Academy class (payment.class_type) the webinar teaches, when known for certain: an exact title match, or an
  -- administrator's explicit choice. NULL means unresolved: runs then have no expected students and nobody is marked absent.
  academy_class_name VARCHAR(200),
  class_source VARCHAR(20),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  added_by UUID NOT NULL,
  added_at TIMESTAMPTZ NOT NULL,
  stopped_by UUID,
  stopped_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_zoom_webinar_monitors_webinar UNIQUE (academy_id, webinar_id),
  CONSTRAINT uq_zoom_webinar_monitors_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_zoom_webinar_monitors_id CHECK (webinar_id ~ '^[0-9]{9,20}$'),
  CONSTRAINT ck_zoom_webinar_monitors_sync CHECK (sync_status IN ('OK', 'FAILED', 'NOT_FOUND')),
  CONSTRAINT ck_zoom_webinar_monitors_class CHECK ((academy_class_name IS NULL) = (class_source IS NULL)),
  CONSTRAINT ck_zoom_webinar_monitors_class_source CHECK (class_source IS NULL OR class_source IN ('EXACT_TITLE', 'ADMIN')),
  CONSTRAINT ck_zoom_webinar_monitors_duration CHECK (duration_minutes IS NULL OR duration_minutes > 0)
);
CREATE INDEX ix_zoom_webinar_monitors_active ON zoom_webinar_monitors (academy_id, webinar_id) WHERE active;

-- One row per actual run of a monitored webinar, keyed by Zoom's occurrence UUID (the same webinar ID runs on many days).
-- actual_started_at comes from webinar.started (or Zoom's report); until then it is NULL and first_seen_at holds the time
-- of the earliest event seen. Scheduled times are never used as the run's identity or its start.
CREATE TABLE zoom_webinar_runs (
  academy_id UUID NOT NULL REFERENCES academies(id),
  occurrence_uuid VARCHAR(100) NOT NULL,
  webinar_id VARCHAR(20) NOT NULL,
  monitor_id UUID NOT NULL,
  -- The attendance class created for this run (attendance, reports and dashboards work per class).
  class_session_id UUID NOT NULL,
  first_seen_at TIMESTAMPTZ NOT NULL,
  actual_started_at TIMESTAMPTZ,
  actual_ended_at TIMESTAMPTZ,
  state VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_zoom_webinar_runs PRIMARY KEY (academy_id, occurrence_uuid),
  CONSTRAINT uq_zoom_webinar_runs_session UNIQUE (class_session_id),
  CONSTRAINT fk_zoom_webinar_runs_monitor FOREIGN KEY (monitor_id, academy_id) REFERENCES zoom_webinar_monitors (id, academy_id),
  CONSTRAINT fk_zoom_webinar_runs_session FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id),
  CONSTRAINT ck_zoom_webinar_runs_state CHECK (state IN ('LIVE', 'ENDED')),
  CONSTRAINT ck_zoom_webinar_runs_times CHECK (actual_ended_at IS NULL OR actual_started_at IS NULL OR actual_ended_at >= actual_started_at)
);
CREATE INDEX ix_zoom_webinar_runs_webinar ON zoom_webinar_runs (academy_id, webinar_id, first_seen_at DESC);
CREATE INDEX ix_zoom_webinar_runs_live ON zoom_webinar_runs (academy_id) WHERE state = 'LIVE';

-- Classes created for a Zoom run are marked so screens can tell them from classes staff scheduled before this change.
ALTER TABLE class_sessions ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'LUMA',
  ADD CONSTRAINT ck_class_sessions_origin CHECK (origin IN ('LUMA', 'ZOOM_RUN'));
