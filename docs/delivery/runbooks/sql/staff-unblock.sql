-- Unblock a staff member. With a password they sign in with it again (sessions ended by the block stay ended);
-- without one (blocked before activating) the account waits for an invitation: run scripts/bootstrap-owner.sh again
-- for the address. Replace the address, then run with psql against the LUMA database. Recorded in the activity log.
BEGIN;
WITH target AS (
  UPDATE accounts SET status = CASE WHEN password_hash IS NULL THEN 'PENDING_ACTIVATION' ELSE 'ACTIVE' END,
         failed_login_count = 0, locked_until = NULL, auth_version = auth_version + 1, updated_at = now()
  WHERE email = lower(btrim('person@example.com')) AND status = 'DISABLED'
    AND EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = accounts.id AND r.role = 'STAFF')
  RETURNING id, academy_id, status
)
INSERT INTO audit_events (id, academy_id, actor_account_id, action, target_type, target_id, details)
SELECT gen_random_uuid(), academy_id, NULL, 'STAFF_ACCOUNT_ENABLED', 'ACCOUNT', id,
       jsonb_build_object('by', 'operator', 'statusNow', status,
                          'newPasswordRequired', (status = 'PENDING_ACTIVATION')::text)
FROM target;
COMMIT;
