-- Slice 002C: scheduled classes, each shared by one or more batches. A class shared
-- by several batches is ONE session; attendance (later) is keyed by session and
-- student, so a student in two of its batches is still counted once.
CREATE TABLE class_sessions (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  title VARCHAR(200) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  -- IANA zone the class is held in; decides the calendar date used for enrollment checks.
  time_zone VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_class_sessions_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_class_sessions_title_present CHECK (btrim(title) <> ''),
  CONSTRAINT ck_class_sessions_times CHECK (ends_at > starts_at AND ends_at - starts_at <= INTERVAL '12 hours'),
  CONSTRAINT ck_class_sessions_status CHECK (status IN ('SCHEDULED', 'CANCELLED'))
);
CREATE INDEX ix_class_sessions_academy_start ON class_sessions (academy_id, starts_at);

CREATE TABLE session_batches (
  session_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  academy_id UUID NOT NULL,
  CONSTRAINT pk_session_batches PRIMARY KEY (session_id, batch_id),
  CONSTRAINT fk_session_batches_session_same_academy
    FOREIGN KEY (session_id, academy_id) REFERENCES class_sessions (id, academy_id) ON DELETE CASCADE,
  CONSTRAINT fk_session_batches_batch_same_academy
    FOREIGN KEY (batch_id, academy_id) REFERENCES batches (id, academy_id)
);
CREATE INDEX ix_session_batches_batch ON session_batches (batch_id);
