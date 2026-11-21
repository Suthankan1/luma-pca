-- Rollback aid for V30 (two roles), for an operator only. Run it ONLY when deploying code older than V30 again, and
-- BEFORE that older code starts: older code cannot read the STAFF role. It does not undo V30 in Flyway's history;
-- older code still starts because Flyway's default ignore pattern ("*:future") accepts applied migrations it does not
-- know (application.yml sets no other pattern). Redeploying the two-role code later reruns nothing: V30 is recorded,
-- so run V30's statements by hand again in that case.
-- Every STAFF account becomes an owner (the closest former role: full access). Batch assignments are not restored.
BEGIN;
ALTER TABLE account_roles DROP CONSTRAINT ck_account_roles_role;
INSERT INTO account_roles (account_id, role, granted_at)
SELECT account_id, 'ACADEMY_OWNER', granted_at FROM account_roles WHERE role = 'STAFF'
ON CONFLICT (account_id, role) DO NOTHING;
DELETE FROM account_roles WHERE role = 'STAFF';
ALTER TABLE account_roles ADD CONSTRAINT ck_account_roles_role CHECK (role IN (
  'ACADEMY_OWNER', 'ACADEMY_ADMIN', 'INSTRUCTOR', 'ATTENDANCE_OFFICER', 'ANALYST', 'STUDENT'));
COMMIT;
