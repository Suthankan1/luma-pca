# One Classes screen, no Staff page — plan and progress

**Owner request (2026-11-20):** (1) Webinars and Classes become one **Classes** screen that shows only the classes Zoom
has scheduled (and the ones already held), with a small option on the same screen to add a Zoom link to track. Nothing
else. (2) With one STAFF role there is nothing to administer per person, so the **Staff** page goes, completely: the
page and the staff-account API. Adding or blocking a staff member becomes an operator task.

**Progress: 100 %** (all slices done). Branch `feat/two-roles` (builds on the two-role refactor).

| # | Slice | Weight | Status |
|---|---|---|---|
| T0 | Plan | 5 % | Done |
| T1 | Classes screen: Zoom-scheduled and held classes by day, "Track a Zoom link" on the same screen; Webinars page and menu item removed (old link redirects) | 35 % | Done |
| T2 | Staff page and staff-account API removed; invitations route kept (students); operator command adds staff; runbook SQL to block/unblock, tested | 35 % | Done: backend 393 tests, 0 failures |
| T3 | Tests, mock API, e2e, contract, docs | 15 % | Done: contract (110 endpoints match), docs, runbook; Playwright Edge desktop + Pixel 7: 118 passed, 2 skipped |
| T4 | Full verification and commits | 10 % | Done: backend 393/0, frontend 77 unit tests, typecheck, lint, build; commits below |

## Decisions

- **Classes screen** (`/staff/sessions`): two weeks at a time (previous / this / next), grouped by day. Each row is
  either a class Zoom has scheduled and not yet run ("Expected", from the tracked webinars' Zoom schedule) or a class
  that ran (Live / Ended, with "Attendance →"). Classes scheduled in LUMA before Zoom owned schedules still show;
  cancelled ones are left out. Built on the existing `calendarEntries` helper, so Home and Classes agree.
- **Track a Zoom link:** a small form at the top (webinar ID or join link only). Tracked webinars are listed in a
  collapsed "Tracked Zoom links" section with the existing per-webinar actions (refresh, stop/resume, Academy class);
  nothing else from the old Webinars page (live cards, run history) is kept: live classes show as "Live" rows, and run
  history is the list of held classes.
- The batch filter on Classes is dropped (the request was "nothing extra"); the batch page's "Classes for this batch"
  link goes with it.
- `/staff/webinars` redirects to `/staff/sessions`.
- **Staff accounts:** `GET/POST /api/v1/staff-accounts`, `GET /staff-accounts/{id}`, `/enable`, `/disable` are removed
  with the page. `POST /api/v1/accounts/{id}/invitations` stays (student invitations use it).
- **Operator path:** `scripts/bootstrap-owner.sh "Academy" person@example [PRINT|EMAIL]` now adds any staff member (new
  pending STAFF account + invitation, or a fresh invitation for a pending one); it still refuses an address that is
  active or belongs to another account. Blocking and unblocking are SQL statements in the runbook
  `docs/delivery/runbooks/STAFF-ACCOUNTS.md`, covered by an integration test.
- Accepted by the owner: without the page, only someone with database or server access can add or block staff.
