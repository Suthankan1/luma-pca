-- Slice 002B (part 2): batch-scoped staff assignments. A staff role alone grants no
-- batch; an assignment grants read access to one batch in that role.
CREATE TABLE staff_assignments (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  account_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  role VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by UUID REFERENCES accounts(id) ON DELETE SET NULL,
  CONSTRAINT fk_staff_assignments_account_same_academy
    FOREIGN KEY (account_id, academy_id) REFERENCES accounts (id, academy_id) ON DELETE CASCADE,
  CONSTRAINT fk_staff_assignments_batch_same_academy
    FOREIGN KEY (batch_id, academy_id) REFERENCES batches (id, academy_id),
  CONSTRAINT uq_staff_assignments UNIQUE (batch_id, account_id, role),
  CONSTRAINT ck_staff_assignments_role CHECK (role IN ('INSTRUCTOR', 'ATTENDANCE_OFFICER'))
);
CREATE INDEX ix_staff_assignments_account ON staff_assignments (account_id);
