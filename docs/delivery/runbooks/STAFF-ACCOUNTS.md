# Operator runbook — Adding, blocking and unblocking staff

LUMA has one staff role and no staff page (owner decision 2026-11-20, ADR-0007 and
`docs/delivery/CLASSES-AND-STAFF-SIMPLIFY-PLAN.md`). Staff accounts are managed by an operator with access to the
server and the database. Every step is recorded in the academy's activity log without an actor ("by the operator").

## Add a staff member

Run the operator command with the academy's exact name and the person's email (same environment as the API; see
`FIRST-OWNER.md` for the variables):

```sh
scripts/bootstrap-owner.sh "Physics Cube Academy" teacher@academy.example          # prints the link
scripts/bootstrap-owner.sh "Physics Cube Academy" teacher@academy.example EMAIL    # emails it
```

It creates a pending STAFF account and a 72-hour activation link (a credential: send it privately). Running it again
before they activate sends a fresh link and cancels the old one. It refuses an address that is already active, blocked,
a student's, or in another academy.

## Block a staff member (they left, or a password leaked)

Edit the address in [`sql/staff-block.sql`](sql/staff-block.sql) and run it:

```sh
psql "host=<db host> dbname=<db name> user=<db user> sslmode=require" -f docs/delivery/runbooks/sql/staff-block.sql
```

(Use the same database as the API's `DB_URL`; that variable is a JDBC URL, which `psql` does not accept.)

Sign-in is refused and every session ends at once. Their past actions and the attendance they recorded stay. Keep at
least one active staff account per academy:

```sql
SELECT a.email, a.status FROM accounts a JOIN account_roles r ON r.account_id = a.id AND r.role = 'STAFF'
WHERE a.academy_id = (SELECT id FROM academies WHERE name = 'Physics Cube Academy') ORDER BY a.status, a.email;
```

## Unblock

Edit the address in [`sql/staff-unblock.sql`](sql/staff-unblock.sql) and run it the same way. With a password they sign
in again; if they never activated, run the add command again for a new link.

Both SQL files are exercised by `StaffAccountOperatorIntegrationTest`.
