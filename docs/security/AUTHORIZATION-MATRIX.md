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

**As built (002A):** only the first two "Admin" capabilities for courses and batches exist. Owner and Admin may list, read, create and update courses and batches of their own academy through sessions that completed MFA (001F). Instructor, Attendance Officer, Analyst and Student are refused on all of those routes until staff assignments and enrollments (002B) give them scoped access. The academy is always taken from the verified token.

**As built (002B part 1):** Owner and Admin manage students and enrollments of their own academy. A Student reads only `GET /api/v1/me/student` and `GET /api/v1/me/enrollments`, which resolve "own" from the token; students get 403 on every staff route, including for their own record, and never see rosters or classmates. Other staff roles still get 403 until staff assignments exist.

**As built (002B part 2):** Owner and Admin assign Instructors and Attendance Officers to batches by email; the account must be in the academy, not disabled and already hold the role. Assigned staff may read only their batches (`GET /api/v1/batches`, `/batches/{id}`, `/batches/{id}/enrollments`) and their own assignments (`GET /api/v1/me/assignments`); they cannot change batches, enrol students, see assignments or reach courses or students. An unassigned batch returns the same 404 as a missing one. Access requires both the assignment and the role held now, so removing either takes effect on the next request. Analysts still have no batch access.

**Negative acceptance examples:** student A cannot request student B's record by guessing ID; teacher for Batch A cannot export Batch B; staff cannot access another academy's integration; guest/unregistered users are not blindly counted; a student in two batches sees only classes associated with enrollments.

See `docs/architecture/adr/ADR-0004-multi-course-batch.md`.
