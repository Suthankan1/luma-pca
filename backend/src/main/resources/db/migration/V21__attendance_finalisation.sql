-- Slice 004B (part 2): the academy's own attendance rule, and final attendance records.
--
-- LUMA does not choose the rule: an owner records it. Without one, nothing can be finalised.
-- A class is finalised only from Zoom's participant report (never from live events alone).

CREATE TABLE attendance_policies (
  academy_id UUID PRIMARY KEY REFERENCES academies(id),
  -- Present when connected for at least this share of the class time.
  min_connected_percent INTEGER NOT NULL,
  -- Present but late when the first join is more than this many minutes after the start.
  late_after_minutes INTEGER NOT NULL,
  -- The academy's own wording or reference for the approved rule (shown to staff).
  description VARCHAR(500) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_by UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_attendance_policies_percent CHECK (min_connected_percent BETWEEN 1 AND 100),
  CONSTRAINT ck_attendance_policies_late CHECK (late_after_minutes BETWEEN 0 AND 240),
  CONSTRAINT ck_attendance_policies_description CHECK (btrim(description) <> '')
);

-- One row per expected student per finalised class. The facts and the rule used are kept with
-- the outcome, so a later rule change never rewrites history.
CREATE TABLE attendance_records (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  class_session_id UUID NOT NULL,
  student_id UUID NOT NULL,
  -- What the rule decided at finalisation.
  computed_outcome VARCHAR(20) NOT NULL,
  -- The outcome in force: the computed one until a correction changes it (slice 004C).
  outcome VARCHAR(20) NOT NULL,
  connected_seconds INTEGER NOT NULL,
  first_join_at TIMESTAMPTZ,
  seconds_after_start INTEGER,
  rule_min_connected_percent INTEGER NOT NULL,
  rule_late_after_minutes INTEGER NOT NULL,
  finalised_by UUID NOT NULL,
  finalised_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_attendance_records_session_same_academy FOREIGN KEY (class_session_id, academy_id) REFERENCES class_sessions (id, academy_id),
  CONSTRAINT fk_attendance_records_student_same_academy FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT uq_attendance_records_session_student UNIQUE (class_session_id, student_id),
  CONSTRAINT uq_attendance_records_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_attendance_records_computed CHECK (computed_outcome IN ('PRESENT', 'LATE', 'ABSENT')),
  CONSTRAINT ck_attendance_records_outcome CHECK (outcome IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')),
  CONSTRAINT ck_attendance_records_seconds CHECK (connected_seconds >= 0 AND (seconds_after_start IS NULL OR seconds_after_start >= 0))
);
CREATE INDEX ix_attendance_records_student ON attendance_records (academy_id, student_id, finalised_at);
