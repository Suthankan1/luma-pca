# Roles and scoped authorization

**Enforcement:** Spring backend checks academy ownership + action-specific role + course/batch/class membership + record owner. This matrix is a starting proposal and academy owner must approve it.

| Capability | Owner/Admin | Instructor (assigned) | Attendance Officer (assigned) | Analyst (approved) | Student (self) |
| --- | --- | --- | --- | --- | --- |
| Academy settings / Zoom credentials | Owner/Admin | — | — | — | — |
| Create courses/batches/enrollments | Admin | — | — | — | — |
| View scoped batch roster | Admin | Assigned batch | Assigned batch | Aggregate only | — |
| View class attendance | Admin | Assigned class | Assigned class | Aggregate permitted | Own only |
| Correct attendance | Admin | No by default | Assigned class + reason | — | Request only |
| Create pulse questions | Admin | Assigned class | — | — | — |
| Submit portal reactions | — | — | — | — | Enrolled class only |
| Monthly report/export | Admin | Assigned batch | Assigned batch | Approved aggregate | Own history |
| View leaderboard | Policy-dependent | Assigned batch | Policy-dependent | Approved aggregate | Opt-in/consented |
| Change roles | Owner/Admin with escalation | — | — | — | — |

**As built (002A):** only the first two "Admin" capabilities for courses and batches exist. Owner and Admin may list, read, create and update courses and batches of their own academy. Instructor, Attendance Officer, Analyst and Student are refused on all of those routes until staff assignments and enrollments (002B) give them scoped access. The academy is always taken from the verified token.

**Negative acceptance examples:** student A cannot request student B's record by guessing ID; teacher for Batch A cannot export Batch B; staff cannot access another academy's integration; guest/unregistered users are not blindly counted; a student in two batches sees only classes associated with enrollments.

See `docs/architecture/adr/ADR-0004-multi-course-batch.md`.
