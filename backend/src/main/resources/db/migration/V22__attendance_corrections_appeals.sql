-- Slice 004C: corrections to final attendance, and students' appeals.

-- An appeal by a student against one final record. At most one open appeal per record.
CREATE TABLE attendance_appeals (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  record_id UUID NOT NULL,
  student_id UUID NOT NULL,
  message VARCHAR(1000) NOT NULL,
  -- OPEN until staff accept (the record is corrected) or reject it.
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL,
  resolved_by UUID,
  resolved_at TIMESTAMPTZ,
  resolution_note VARCHAR(500),
  CONSTRAINT fk_attendance_appeals_record FOREIGN KEY (record_id, academy_id) REFERENCES attendance_records (id, academy_id),
  CONSTRAINT fk_attendance_appeals_student FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT uq_attendance_appeals_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_attendance_appeals_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
  CONSTRAINT ck_attendance_appeals_message CHECK (btrim(message) <> ''),
  CONSTRAINT ck_attendance_appeals_resolved CHECK ((status = 'OPEN') = (resolved_at IS NULL AND resolved_by IS NULL))
);
CREATE UNIQUE INDEX uq_attendance_appeals_open ON attendance_appeals (record_id) WHERE status = 'OPEN';
CREATE INDEX ix_attendance_appeals_academy_status ON attendance_appeals (academy_id, status, created_at);

-- Every change to a final record's outcome, with its reason. Append-only: history is never rewritten.
CREATE TABLE attendance_corrections (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  record_id UUID NOT NULL,
  previous_outcome VARCHAR(20) NOT NULL,
  new_outcome VARCHAR(20) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  -- Set when the correction accepted a student's appeal.
  appeal_id UUID,
  corrected_by UUID NOT NULL,
  corrected_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_attendance_corrections_record FOREIGN KEY (record_id, academy_id) REFERENCES attendance_records (id, academy_id),
  CONSTRAINT fk_attendance_corrections_appeal FOREIGN KEY (appeal_id, academy_id) REFERENCES attendance_appeals (id, academy_id),
  CONSTRAINT ck_attendance_corrections_outcomes CHECK (previous_outcome IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')
    AND new_outcome IN ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED') AND previous_outcome <> new_outcome),
  CONSTRAINT ck_attendance_corrections_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_attendance_corrections_record ON attendance_corrections (record_id, corrected_at);

CREATE FUNCTION attendance_corrections_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'attendance_corrections is append-only';
END;
$$;
CREATE TRIGGER attendance_corrections_no_update_or_delete BEFORE UPDATE OR DELETE ON attendance_corrections
  FOR EACH ROW EXECUTE FUNCTION attendance_corrections_append_only();
