-- Incremental baseline only; accounts, enrollments, webinars, attendance and events
-- get their own reviewed migrations in future vertical slices.
CREATE TABLE academies (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE courses (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  code VARCHAR(60) NOT NULL,
  title VARCHAR(200) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_courses_academy_code UNIQUE (academy_id, code)
);
CREATE INDEX ix_courses_academy ON courses (academy_id);
