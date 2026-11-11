-- Slice 002B (part 1): student records and batch enrollments.
-- Target for the composite foreign key that keeps a student's account in the student's academy.
ALTER TABLE accounts ADD CONSTRAINT uq_accounts_id_academy UNIQUE (id, academy_id);

-- One academy identity per person, never duplicated per batch or course.
CREATE TABLE students (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  account_id UUID NOT NULL,
  -- The academy's own stable student ID; never a Zoom display name.
  student_number VARCHAR(60) NOT NULL,
  full_name VARCHAR(200) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_students_account_same_academy FOREIGN KEY (account_id, academy_id) REFERENCES accounts (id, academy_id),
  CONSTRAINT uq_students_account UNIQUE (account_id),
  CONSTRAINT uq_students_academy_number UNIQUE (academy_id, student_number),
  CONSTRAINT uq_students_id_academy UNIQUE (id, academy_id),
  CONSTRAINT ck_students_number_normalized CHECK (student_number <> '' AND student_number = upper(btrim(student_number))),
  CONSTRAINT ck_students_name_present CHECK (btrim(full_name) <> '')
);

-- A student may belong to several batches, including several of one course.
-- Withdrawal keeps the row so history and later attendance keep their context.
CREATE TABLE enrollments (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  batch_id UUID NOT NULL,
  student_id UUID NOT NULL,
  enrolled_on DATE NOT NULL,
  withdrawn_on DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_enrollments_batch_same_academy FOREIGN KEY (batch_id, academy_id) REFERENCES batches (id, academy_id),
  CONSTRAINT fk_enrollments_student_same_academy FOREIGN KEY (student_id, academy_id) REFERENCES students (id, academy_id),
  CONSTRAINT uq_enrollments_batch_student UNIQUE (batch_id, student_id),
  CONSTRAINT ck_enrollments_dates CHECK (withdrawn_on IS NULL OR withdrawn_on >= enrolled_on)
);
CREATE INDEX ix_enrollments_student ON enrollments (student_id);
