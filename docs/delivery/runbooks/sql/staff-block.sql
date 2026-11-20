-- Block a staff member: refuses sign-in, ends every session at once (each API request re-reads the account) and
-- cancels any unused activation link.
-- Replace the address, then run with psql against the LUMA database. Does nothing for an address that is not a
-- staff account, or one already blocked. Recorded in the activity log as "Disabled ..." with no actor (the operator).
-- Keep at least one active staff account per academy: check the list in STAFF-ACCOUNTS.md first.
BEGIN;
WITH target AS (
  UPDATE accounts SET status = 'DISABLED', auth_version = auth_version + 1, updated_at = now()
  WHERE email = lower(btrim('person@example.com')) AND status <> 'DISABLED'
    AND EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = accounts.id AND r.role = 'STAFF')
  RETURNING id, academy_id
), ended AS (
  UPDATE refresh_sessions s SET revoked_at = now(), revoke_reason = 'LOGOUT_ALL'
  FROM target WHERE s.account_id = target.id AND s.revoked_at IS NULL
  RETURNING s.id
), cancelled AS (
  -- An activation link sent before the block must not work afterwards, even once unblocked.
  UPDATE account_invitations i SET revoked_at = now()
  FROM target WHERE i.account_id = target.id AND i.consumed_at IS NULL AND i.revoked_at IS NULL
  RETURNING i.id
)
INSERT INTO audit_events (id, academy_id, actor_account_id, action, target_type, target_id, details)
SELECT gen_random_uuid(), academy_id, NULL, 'STAFF_ACCOUNT_DISABLED', 'ACCOUNT', id,
       jsonb_build_object('by', 'operator', 'sessionsEnded', (SELECT count(*) FROM ended)::text)
FROM target;
COMMIT;
