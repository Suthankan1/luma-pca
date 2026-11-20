# Two roles: STAFF and STUDENT — refactor plan and progress

**Owner decision (2026-11-20):** LUMA has exactly two roles. `STAFF` can do everything staff can do today (the old
owner level, across every course and batch). `STUDENT` is unchanged. The five staff roles (`ACADEMY_OWNER`,
`ACADEMY_ADMIN`, `INSTRUCTOR`, `ATTENDANCE_OFFICER`, `ANALYST`) and batch assignments go away.

**Progress: 100 %** (all slices done; live run on the Supabase database waits for the owner). Updated after every slice. Work happens on branch `feat/two-roles` in the worktree `.claude/worktrees/two-roles`, branched from `0674ff1`, so the open work in the main checkout is untouched.

| # | Slice | Weight | Status |
|---|---|---|---|
| S0 | Analysis, plan, branch | 5 % | Done |
| S1 | Database migration + `Role` enum + `CurrentAccount` + `SecurityConfig` | 15 % | Done |
| S2 | Remove batch scoping and staff assignments (backend) | 15 % | Done |
| S3 | Identity: staff accounts, bootstrap, notices, owner-only actions | 10 % | Done |
| S4 | Backend tests rewritten; full `mvn test` green | 20 % | Done: 402 tests, 0 failures (Testcontainers PostgreSQL 17) |
| S5 | Frontend: role helpers, every staff page, team page, batches page, mock API, unit + e2e tests | 20 % | Done: 79 unit tests; Playwright Edge desktop + Pixel 7: 126 passed, 2 skipped |
| S6 | Contracts, docs, scripts, checkpoint | 10 % | Done: contract (115 endpoints match), matrix, ADR-0007, guides, scripts, checkpoint |
| S7 | Final verification (all suites, smoke, live local check) | 5 % | Done except the live run on the Supabase database (needs the owner's go-ahead: it applies V30 to real accounts) |

## 1. Analysis (what exists today)

### Roles and where they are checked
- `Role` enum: 6 values. `account_roles.role` has CHECK `ck_account_roles_role` (V2). Roles are read from the database
  on every request (`AccountJwtAuthenticationConverter`, `RefreshSessions`), never from the token, so changing the
  stored roles takes effect without re-issuing tokens.
- `CurrentAccount.isAcademyManager()` / `requireAcademyManager()` (owner or admin): used in 27 backend files.
  `batchScopedRoles()` / `BATCH_SCOPED_ROLES` (instructor, attendance officer): `BatchService`, `StaffAssignmentService`,
  `AttendancePolicyService`.
- `SecurityConfig`: 10 `hasAnyRole(...)` gates with different role mixes.
- Owner-only rules: attendance rule (`AttendancePolicyService.set`), engagement on/off (`EngagementService.configure`),
  student erasure (`StudentErasureService.erase`), granting/managing owner and admin accounts and "keep one active owner"
  (`StaffAccountService`, `AccountRepository.activeOwnersOtherThan`), automatic finalisation runs as an active owner
  (`AttendanceQueueService.autoFinaliseOnce`, raw SQL on `'ACADEMY_OWNER'`), first-owner bootstrap (`OwnerBootstrapService`).
- Role-specific rules: corrections (attendance officer), pulse questions (instructor), academy totals (analyst),
  unexpected visitors shown to managers only (`ProvisionalAttendanceService`), manager notices (`AccountNotifier`).
- Batch scoping: `staff_assignments` (V8), `StaffAssignment*` (4 routes: list/add per batch, delete, `GET /me/assignments`),
  `BatchScope` used by `BatchService` and `ClassSessionService` (lists, reads, latest completed class, expected students).

### Frontend
- `session-policy.ts` (`staffRoles`, `hasPortal`), `staff-forms.ts` (`managerRoles`, `isAcademyManager`, role labels,
  grantable roles, assignment validation), `activity.ts` (role labels in audit history).
- 23 pages/components branch on roles (`isAcademyManager` ~40 times; owner/analyst/instructor/officer checks on Home,
  Attendance, class page, student page, Team). Team page has role pickers; batch page has the assignment section.
- Tests: `session-policy`, `staff-forms`, `activity` unit tests; `staff-team` e2e; mock API (`mock-auth-runner.mjs`).

### Tests, contracts, docs, scripts
- 46 backend test classes reference staff roles (~300 references); many are "instructor of batch A cannot read batch B"
  negatives that become obsolete.
- `contracts/openapi/platform-v1.yaml` (role enum, staff/assignment routes), `docs/security/AUTHORIZATION-MATRIX.md`,
  `docs/security/JWT-AUTH.md`, blueprint docs, `scripts/auth-stack-smoke.sh`, `scripts/load-test-local.sh`.

## 2. Decisions

1. **Roles:** `Role { STAFF, STUDENT }`. A `STAFF` account may do every staff action in its own academy. Academy
   isolation (`academy_id` from the token) and student "own data only" rules stay exactly as they are.
2. **Migration `V30__two_roles.sql`** (append-only; V2/V8 untouched): drop the CHECK, give every account holding any old
   staff role one `STAFF` row (earliest `granted_at`), delete the old rows, re-add the CHECK as `('STAFF','STUDENT')`.
   An account that also holds `STUDENT` keeps it (behaviour unchanged).
3. **Batch assignments:** routes, service, entity and UI removed. The `staff_assignments` table is **kept, unread** so
   history is not destroyed; dropping it can be a later, separate migration.
4. **Safety guards that remain** (staff administration still locks the academy row):
   - nobody disables themselves;
   - the academy always keeps at least one active `STAFF` account (replaces "one active owner");
   - students are never managed through staff routes; every action is audited as before.
5. **Staff account API:** `POST /api/v1/staff-accounts` takes only `email`; `PUT /api/v1/staff-accounts/{id}/roles` is
   removed (nothing left to change). Enable/disable/invite keep their routes.
6. **Former owner-only actions** (attendance rule, engagement switch, erasure with typed confirmation) become staff
   actions. Auto-finalisation runs as the staff member who switched it on, while that account is active and `STAFF`.
7. **Bootstrap:** the operator command keeps its name and env vars but creates the first `STAFF` account and refuses when
   the academy already has an active staff account. Audit action names stay (`OWNER_BOOTSTRAPPED`, …) for continuity.
8. **Audit history:** old events still contain old role names; the activity page keeps labels for them.
9. **Rollout:** backend and frontend ship together (the frontend recognises only `STAFF`). Not yet deployed to
   production; the local Supabase-backed database is migrated by Flyway at backend start.

## 3. Risks and how they are handled

| Risk | Handling |
|---|---|
| Former instructors/officers/analysts gain full access | Intended by the owner. Before rollout, review the staff list; disable anyone who should not have full access. |
| Any staff member can disable another or erase a student | Accepted by the owner. Self-disable and last-active-staff guards remain; erasure still needs the typed student number and a reason; everything is audited. |
| Unknown role string left in the database crashes role loading | Migration converts every row and re-adds the CHECK; Testcontainers test migrates seeded old roles. |
| Rollback | Old code cannot read `STAFF`. Roll back with a reverse SQL script (documented in the checkpoint) that maps `STAFF` to `ACADEMY_OWNER`, then revert the commits. |
| Open work in the main checkout (staff home "latest completed" slice) touches the same files | Separate worktree; on merge, resolve the overlap in `SecurityConfig`, `ClassSessionService`, the OpenAPI contract, the authorization matrix and Staff Home (the new `latest-completed` route becomes STAFF-only like every staff route). |

## 4. Verification plan
- Backend: `mvn test` (Testcontainers PostgreSQL), including a migration test from old roles and new negative tests
  (student on staff routes → 403, other academy → 404, self-disable and last-staff → refused).
- Frontend: `npm test`, typecheck, lint, production build, `npm run test:smoke`, Playwright desktop + Pixel 7 on Edge
  with the mock backend.
- OpenAPI lint must not add errors; `scripts/verify_structure.py`.
- Local run: backend against the local database, sign in as staff, open Team, Batches, a class, Attendance.
