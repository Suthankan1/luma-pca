-- Owner decision 2026-11-20: LUMA has two roles. STAFF does every staff action in its academy; STUDENT is unchanged.
-- Every account holding any former staff role (owner, administrator, instructor, attendance officer, analyst) gets one
-- STAFF row, granted when its earliest staff role was; the former rows are removed. An account that also holds STUDENT
-- keeps it. staff_assignments is no longer read (staff see every batch) but is kept for history.
ALTER TABLE account_roles DROP CONSTRAINT ck_account_roles_role;

INSERT INTO account_roles (account_id, role, granted_at)
SELECT account_id, 'STAFF', MIN(granted_at)
FROM account_roles
WHERE role IN ('ACADEMY_OWNER', 'ACADEMY_ADMIN', 'INSTRUCTOR', 'ATTENDANCE_OFFICER', 'ANALYST')
GROUP BY account_id
ON CONFLICT (account_id, role) DO NOTHING;

DELETE FROM account_roles
WHERE role IN ('ACADEMY_OWNER', 'ACADEMY_ADMIN', 'INSTRUCTOR', 'ATTENDANCE_OFFICER', 'ANALYST');

ALTER TABLE account_roles ADD CONSTRAINT ck_account_roles_role CHECK (role IN ('STAFF', 'STUDENT'));

COMMENT ON TABLE staff_assignments IS 'Retired by V30 (two roles): kept for history, no longer read or written.';
