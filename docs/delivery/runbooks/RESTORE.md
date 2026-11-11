# Operator runbook — Database restore (planned)

Document AWS RDS automated backup/PITR configuration, RPO/RTO, immutable restore evidence and staging restore drill before production. Never test restore against the only live database. On incident: restrict writes, snapshot evidence, restore to a new private instance, validate migration checksum, idempotent inbox receipts, account session revocation and audit continuity; switch traffic under approved change; reconcile any Zoom events within the recovery gap.

The provided Docker Compose local volume is **not a production backup system**.
