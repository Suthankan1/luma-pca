# Operator runbook — Database backup and restore

## What exists now (tested)

- **Logical backup:** `PGPASSWORD=… scripts/db-backup.sh <host> <port> <database> <user>` writes a
  `pg_dump` custom-format file to `backups/` (gitignored, folder 700, file 600). The dump holds personal
  data and password hashes: store it encrypted and access-controlled, never commit, email or paste it,
  and delete it when its retention period ends.
- **Restore drill:** `PGPASSWORD=… scripts/db-restore-drill.sh <host> <port> <database> <user>` dumps
  the source (read-only), restores into a throwaway PostgreSQL 17 container with no published port,
  and checks: the Flyway history (versions, checksums, success) is identical; every table's row count
  matches (a busy table can differ by rows written after the dump started — the report names it); the
  audit log's append-only trigger is present and refuses an update. The dump and container are deleted
  afterwards. Run it after every schema change and on a schedule once in production.
- **In CI:** `BackupRestoreIntegrationTest` seeds data from every area through the API, dumps it,
  restores it into a second PostgreSQL 17, runs Flyway `validate` against the copy with this build's
  migrations (none pending), compares every table's full contents (row count and content hash), and
  checks the restored audit log refuses an update.

First drill: 13 Nov 2026 against the Zoom test database — 15 migrations identical, 23 tables and 62 rows
matching, append-only trigger present (an update of the 6 audit events was refused). PASSED.

## Still to decide before production

The academy has not yet answered the RPO, RTO and retention question (OPEN-QUESTIONS P1) or the hosting
budget (P0). The production plan remains managed backups with point-in-time recovery (for example RDS
automated backups) as the primary copy, with this logical backup for moves between hosts and as an
independent copy, plus a scheduled restore drill against a non-production instance.

## On incident

Never test a restore against the only live database. Restrict writes, snapshot evidence, restore to a new
private instance, run the drill checks against it (migration checksums, table counts, audit continuity),
revoke account sessions if credentials may be involved, switch traffic under an approved change, and
reconcile any Zoom events within the recovery gap (webhook receipts are idempotent, so replays are safe).

The Docker Compose local volume is **not a production backup system**.
