# ADR-0007: Two roles, STAFF and STUDENT

**Status:** Accepted (owner decision, 2026-11-20). Supersedes the staff-role and staff-assignment parts of ADR-0004 and
the "persisted assignments" wording of ADR-0002.

**Context:** LUMA had five staff roles (owner, administrator, instructor, attendance officer, analyst) and batch-scoped
staff assignments. The academy's staff all do every kind of staff work, so the roles added setup work and confused
people ("why can't I see this batch?") without protecting anything the academy needs.

**Decision:** an account holds `STAFF`, `STUDENT` or both. `STAFF` may do every staff action in its own academy, across
all courses and batches. `STUDENT` reaches only its own records. Migration V30 maps every former staff role to `STAFF`;
`staff_assignments` is kept for history but no longer read. Roles are still read from the database on every request.

**Guards kept:** academy isolation from the verified token (another academy's records answer 404); students get 403 on
every staff route; every change is audited.

**Follow-up (same day, owner):** with nothing per person to administer, the staff page and the staff-account API were
removed. The operator adds staff with `scripts/bootstrap-owner.sh` and blocks or unblocks them with the runbook SQL
(`docs/delivery/runbooks/STAFF-ACCOUNTS.md`).

**Consequences:** every staff member can erase a student's data, change the attendance rule and see every student of
the academy. The owner accepted this risk; the audit log is the control. Rolling back needs a reverse
data script (map `STAFF` to `ACADEMY_OWNER`) before deploying older code, because older code cannot read `STAFF`.
