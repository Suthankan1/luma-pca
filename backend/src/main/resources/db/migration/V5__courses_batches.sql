-- Slice 002A: course management columns and batches.
ALTER TABLE courses
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  -- Codes are compared exactly, so they are stored in one canonical form.
  ADD CONSTRAINT ck_courses_code_normalized CHECK (code <> '' AND code = upper(btrim(code))),
  ADD CONSTRAINT ck_courses_title_present CHECK (btrim(title) <> ''),
  -- Target for the composite foreign key that keeps a batch in its course's academy.
  ADD CONSTRAINT uq_courses_id_academy UNIQUE (id, academy_id);

CREATE TABLE batches (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  course_id UUID NOT NULL,
  code VARCHAR(60) NOT NULL,
  name VARCHAR(200) NOT NULL,
  starts_on DATE NOT NULL,
  ends_on DATE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_batches_course_same_academy FOREIGN KEY (course_id, academy_id) REFERENCES courses (id, academy_id),
  CONSTRAINT uq_batches_course_code UNIQUE (course_id, code),
  -- Target for later composite foreign keys (enrollments, staff assignments, session batches).
  CONSTRAINT uq_batches_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_batches_code_normalized CHECK (code <> '' AND code = upper(btrim(code))),
  CONSTRAINT ck_batches_name_present CHECK (btrim(name) <> ''),
  CONSTRAINT ck_batches_dates CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_batches_academy ON batches (academy_id);
