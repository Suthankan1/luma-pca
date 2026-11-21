# Roles and scoped authorization

**Current model (owner decision 2026-11-20): two roles, `STAFF` and `STUDENT`.** The five staff roles (owner,
administrator, instructor, attendance officer, analyst) and batch assignments are gone; migration V30 gave every former
staff account the one `STAFF` role on databases migrated to V30. See `docs/delivery/TWO-ROLES-REFACTOR-PLAN.md`.

**Enforcement:** the Spring backend checks, on every request, the academy (always from the verified token) + the role
(read from the database, never from the token) + record ownership for students. `SecurityConfig` sends every staff route
through one `hasRole("STAFF")` rule; services repeat the check (`CurrentAccount.requireStaff()`) and bind every query to
the caller's academy, so records of another academy answer 404.

| Capability | Staff | Student (self) |
| --- | --- | --- |
| Courses, batches, students, enrollments, imports, invitations | Every record of their academy | — (403) |
| Classes, rosters, expected students, attendance, corrections, exports, reports, batch totals | Every class and batch of their academy | Own classes and own records only |
| Attendance rule, finalisation, recalculation, appeals | Yes | Appeal own record |
| Zoom: webinar monitoring, occurrences, participants, receipts, reports | Yes | — |
| Engagement switch, pulse questions, rankings | Yes | Own consent, reactions and answers (enrolled class only) |
| Student erasure | Yes (student number typed again, reason, audited) | — |
| Staff accounts | No API (owner decision 2026-11-20): the operator adds, blocks and unblocks staff | — |
| Invitations for pending accounts (students) | Yes | — |
| Activity log | Yes | — |

**Guards kept:** staff accounts cannot be changed over HTTP at all (the operator command adds them; the runbook SQL in
`docs/delivery/runbooks/sql` blocks and unblocks them, ending every session and cancelling unused activation links).
Students are never managed through staff routes. Every change is audited (actor, or none for the operator; action,
target id; no email or token). A former role name in any request or token grants nothing.

**Accepted risk (owner):** every staff member has full staff access, including erasing a student's data and changing the
attendance rule. The audit log records who did what. Adding or blocking staff needs someone with server or database
access.

**Negative acceptance examples:** student A cannot request student B's record by guessing an ID; a student gets 403 on
every staff route; staff of one academy cannot read or change another academy's records (404); guest or unregistered
Zoom users are not counted as students; a student in two batches sees only classes of their enrollments.

Staff can read their academy’s dated class totals, latest completed class, and last five completed classes. Shared classes
count each student once. `GET /api/v1/class-sessions/{sessionId}/attendance?includeOnlineHistory=true` adds timestamps and
counts of matched students from recorded Zoom visits for the requested class, including matched students outside its roster.

**Student calendar and live reads.** The calendar includes future slots only for active, successfully synced Zoom monitors
linked to a batch the caller is enrolled in on the slot date. Live attendance checks the same dated class enrollment,
the class window and current Zoom evidence. It exposes only distinct totals for the caller's batch scope and the caller's
own observed join and connected time. No other participant details or roster are returned. Ended, cancelled and out-of-scope
classes return 404 for live attendance. Both routes require STUDENT and return private, no-store responses.

## History (superseded by the two-role model)

The notes below describe the role model before 2026-11-20 and are kept for the record.

**As built (002A):** only the first two "Admin" capabilities for courses and batches exist. Owner and Admin may list, read, create and update courses and batches of their own academy through any live session (the MFA-session requirement of 001F was removed on 2026-11-20). Instructor, Attendance Officer, Analyst and Student are refused on all of those routes until staff assignments and enrollments (002B) give them scoped access. The academy is always taken from the verified token.

**As built (002B part 1):** Owner and Admin manage students and enrollments of their own academy. A Student reads only `GET /api/v1/me/student` and `GET /api/v1/me/enrollments`, which resolve "own" from the token; students get 403 on every staff route, including for their own record, and never see rosters or classmates. Other staff roles still get 403 until staff assignments exist.

**As built (002B part 2):** Owner and Admin assign Instructors and Attendance Officers to batches by email; the account must be in the academy, not disabled and already hold the role. Assigned staff may read only their batches (`GET /api/v1/batches`, `/batches/{id}`, `/batches/{id}/enrollments`) and their own assignments (`GET /api/v1/me/assignments`); they cannot change batches, enrol students, see assignments or reach courses or students. An unassigned batch returns the same 404 as a missing one. Access requires both the assignment and the role held now, so removing either takes effect on the next request. Analysts still have no batch access.

**As built (002C):** Owner and Admin create and edit class sessions. Instructors and Attendance Officers read sessions that include a batch they are assigned to; they see only their own batches in the session and only those batches' students in the expected list, so a shared class never reveals another batch. A session with none of their batches returns 404. Students and Analysts have no session access yet.

**As built (staff administration):** Owner and Admin list staff, create staff accounts, change roles, disable accounts and issue activation invitations for pending staff and student accounts. Only an Owner may grant, change or remove the Owner or Admin role, or disable or invite such an account. Nobody changes their own roles or disables themselves. The academy always keeps one active Owner; every change locks the academy row and re-reads the caller's roles under that lock, so two owners removing each other at once leave one. Students are never managed through staff routes. Each action writes an append-only audit event (actor, action, target id; no email or token).

**Negative acceptance examples:** student A cannot request student B's record by guessing ID; teacher for Batch A cannot export Batch B; staff cannot access another academy's integration; guest/unregistered users are not blindly counted; a student in two batches sees only classes associated with enrollments.

See `docs/architecture/adr/ADR-0004-multi-course-batch.md`.

**As built (FIX-10, reports):** `GET /api/v1/batches/{id}/attendance/summary` (each student's counts and rate) for Owner,
Admin and staff assigned to the batch (404 otherwise). `GET /api/v1/attendance/summary` (totals per batch, no student
numbers, names or emails) for Owner, Admin and Analyst. Analysts may also read the attendance rule; they still have no
batch, roster, class or student access.

**As built (ACAD-1, Academy classes):** `GET /api/v1/academy/classes`, `PUT /api/v1/academy/classes/link` and
`POST /api/v1/academy/classes/sync` are Owner and Admin only (403 for every other role, 401 without a token). A link
must name a batch of the caller's academy (404 otherwise). The Academy database is read through a separate
column-limited read-only role; nothing is written to it.

**As built (ACAD-2, student ID sign-in):** see `docs/security/STUDENT-ID-LOGIN.md`. The session it opens is an
ordinary student session: the same "own data only" rules and the same 403s on staff routes apply. Accounts holding
any role other than STUDENT are refused.

**As built (ATT-DASH, attendance dashboards):** `GET /api/v1/attendance/dashboard` for Owner, Admin, Instructor and
Attendance Officer; the class list and expected students are limited to the caller's batches, so assigned staff see
only their batches' students. `GET /api/v1/students/{id}/attendance-overview` for Owner and Admin (404 outside the
academy). `GET /api/v1/me/attendance-overview` for Students, resolved from the token only; students get 403 on the
staff routes.

**Staff monthly directory.** `GET /api/v1/attendance/student-directory` requires `STAFF`. The backend resolves the academy from the authenticated account. The response contains directory names and registration numbers with participation counts, uses `private, no-store`, and excludes account emails and per-session identities.

**Staff live reads.** The BFF checks the staff portal session for `/staff/live` and `/staff/sessions/{id}/live`. The API rechecks access to each class. The dashboard response contains totals and sample times. Session detail also includes its scoped attendance rows. Both responses are private and no-store.
