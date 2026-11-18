# Claude Code autonomous handoff — LUMA Zoom Webinar ID-only monitoring

> Paste the entire text below into Claude Code in the checked-out `Suthankan1/luma-pca` repository. The Academy confirmed these requirements on **2026-11-18**. Complete every feasible task in a continuous, evidence-driven sequence. Do not request permission after each slice; stop only at a real blocker requiring human authorization or business judgement.

## Mission

Implement and fully verify the **Zoom Webinar-ID-only, Zoom-owned schedule, actual-start-driven live attendance** redesign for Physics Cube Academy. LUMA is a **read-only monitoring/attendance consumer of Zoom**, not a scheduling product. Admins provide only a Webinar ID or supported webinar link. LUMA fetches the webinar title/schedule/recurrence from the academy-authorized Zoom account. A webinar actually started early, late, or without a schedule must create a distinct LUMA run automatically via signed Zoom webhooks. Students' joined/left/rejoined durations and real-time status appear in staff/student dashboards. End-of-webinar official reports remain the final source used by existing academy-approved attendance policy.

Read **in full, in order**: `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/ai/START-HERE.md`, `docs/ai/checkpoints/PROGRESS.md`, `docs/integration/ZOOM-RULES.md`, `docs/integration/ACADEMY-PAYMENTS-ROSTER.md`, then the three new requirement/design/plan documents:

1. `docs/product/ZOOM-WEBINAR-ID-ONLY-ACCEPTANCE.md`
2. `docs/architecture/ZOOM-WEBINAR-AUTO-SESSIONS.md`
3. `docs/delivery/WEBINAR-AUTO-TRACKING-IMPLEMENTATION.md`

Newest Academy requirements in these three documents supersede **old schedule-dependent product assumptions**, but existing audited security, Zoom privacy, report reconciliation and historical data invariants remain applicable.

## Repository and Git discipline

- Repository: `https://github.com/Suthankan1/luma-pca`; target branch: `main`. First inspect `git status --short --branch`, `git log`, remotes, open PRs and differences against the latest origin/main. Do not discard any unrelated/uncommitted work.
- Planning docs are on branch `docs/zoom-webinar-id-only-auto-20261118`, with a PR to `main` (not merged by this handoff). If the docs branch is still open, create a new **implementation feature branch from that docs branch** so you retain the specifications; if already merged, branch from current `origin/main`. Do not edit protected `main` directly.
- Implement **all WID-00 through WID-08 slices** in `docs/delivery/WEBINAR-AUTO-TRACKING-IMPLEMENTATION.md`, progressing from backend domain/security/data model through UI and end-to-end verification. One small verified commit per slice, push the implementation feature branch and open a PR against `main` when complete. Do not merge it, enable auto-merge, redeploy production or change Zoom/Academy production settings. User approval to make a PR is **not** approval to deploy.
- Keep going on your own through analysis → tests → code → checks → small commit → next task. Do **not** stop merely to ask "should I continue?". When truly blocked, still complete independent safe work; pause only for missing credentials/rights, external production action, unverifiable ownership/course identity decisions, or impossible tests. At environment/context limits write a complete precise checkpoint and resume if available; never claim continuous execution after your session ends.

## Existing implementation: extend it, do not rebuild

The repo already has Java 21/Spring Boot 4.1.2, Next.js 16, PostgreSQL/Flyway, signed `webinar.started/ended/participant_joined/participant_left` webhook ingress, durable Zoom receipt worker and replay, occurrence UUID evidence and identity links, official post-webinar participant report reconciliation, staff/student attendance dashboard APIs, Zoom S2S OAuth read client, and Academy `public.payment`/`public.student` read-only roster integration. Confirm the actual merged code and tests before implementing. In particular, an older `OccurrenceLinker` and `class_sessions` workflow appear to depend on a LUMA `SCHEDULED` session within a +-60-minute window and current attendance clips to scheduled class time. This MUST be replaced/adapted for actual-start-created runs; otherwise an early or unscheduled webinar is silently missed or credited incorrectly.

Likely files (verify, not assumed exhaustive):

- `backend/src/main/java/com/physicscube/luma/zoom/` — occurrence linking, webhook worker, webinar fetch/report API
- `backend/src/main/java/com/physicscube/luma/attendance/` and `/sessions/` — interval union/clipping, session persistence, report finalization
- `frontend/src/components/staff/SessionForms.tsx` and `frontend/src/app/staff/sessions/actions.ts` — existing manual scheduling fields to retire from UX
- `frontend/src/app/staff/zoom/`, including `/import/`, staff/student attendance pages — convert to read-only Webinar ID/link monitor and Zoom-owned timetable
- `docs/ai/checkpoints/PROGRESS.md`, OpenAPI contract, Zoom rules, integration/delivery docs — refresh as implementation becomes verified

## Implementation invariants

**Zoom is authoritative for scheduling:** no LUMA create, edit, move, reschedule, cancel, title-edit, start/stop Zoom API, or in-LUMA weekly class series management. The input for monitoring is **only the Webinar ID or a supported link**, not a user-entered date/title/time. Parse links safely and validate Zoom ownership through existing authorized S2S OAuth with only required read scopes. Show the Zoom title, timezone, scheduled metadata and occurrences read-only, refresh changes, and indicate stale/unavailable data. Some webinars have no scheduled time: handle them honestly.

**Actual event is authoritative for attendance:** persisted signed `webinar.started` event activates a run even when Zoom has an 18:00 schedule but host starts at 16:00, or a Zoom occurrence has no schedule. Use `(academy ID, Zoom account/host, occurrence UUID)` as a durable identity; Webinar ID alone is reused. Process real joined/left event timestamps under that run, recover if a participant event arrives before started, and preserve high-availability behavior when no LUMA browser is open. Show LIVE based on stored run state, not scheduled time.

**Correct time accounting:** preserve every rejoin visit; union overlap, count `16:05–16:40` + `16:45–17:30` = 80 minutes. Cap to the **actual** webinar window, not scheduled start/end. Do not infer attention. Reconcile each UUID with the existing official Zoom report after end; delayed reports, retries, duplicate/out-of-order events and missing webhooks must not double-count or claim an unverified absence. Preserve the versioned Academy attendance rule and audit trail.

**Course and student identity:** existing Academy `public.payment` (singular) provides `pcaid`, `type`, `class_type`, `deleted_at`; existing `public.student` provides approved `pcaid`, `name`, `mail`, `deleted_at`. No Academy DB writes, no Academy attendance table reads, no financial/private columns. If a Zoom title cannot be matched **uniquely and reliably** to an Academy class, keep an unresolved association; do not guess or mark students absent. Display-only webinar metadata may still appear, and raw Zoom participant evidence may still be collected securely. Match people only through reliable Zoom registration/email or existing audited reviewer decisions; a free-text Zoom display name is never sufficient.

**Security:** Keep current staff authorization, student own-data boundary, sealed browser cookies, existing MFA and authentication code unless specifically required by the new feature. The Academy's pre-existing student-ID login decision is separate; do not broaden this change to rewrite authentication. No credentials in source/logs/frontend, no bypass of RLS or role permissions; no new Zoom credential set or license. Verify signed webhook/timestamp, durable receipt, retry/replay and cross-academy isolation. Do not implement invasive monitoring or unauthorized cloud/video recording.

## How to work

1. Inspect current tests and domain. Write a short audit identifying affected APIs, classes, schema, session state transitions and current schedule-dependent assumptions. State what is already implemented instead of claiming everything is missing.
2. Execute the WID slices in order unless dependency analysis proves a safer ordering. For each: define acceptance test, change minimal production code, test denial/error/edge paths, run relevant checks, inspect diff, commit and keep moving. Favor additive migrations and adapters; never edit applied Flyway migrations, remove historical attendance, or overwrite user changes.
3. Use real tests with synthetic data and mocked Zoom requests. Use the existing Testcontainers and Next unit/Playwright harness; check `mvn -f backend/pom.xml test`, `npm --prefix frontend run lint`, `npm --prefix frontend run build`, `python3 scripts/verify_structure.py`, OpenAPI verification, and real-stack smoke tests wherever available. Capture genuine pass/fail counts. If Docker/network is unavailable, distinguish unrun tests from green tests.
4. Implement safe idempotent live refresh and a clear Zoom-origin schedule versus actual-run time in the UI. On mobile, all attendance values and join/rejoin details remain usable. Remove all scheduling/name/time edit controls from user-visible flows only once replacement is working, not by merely hiding links while leaving unguarded public mutations.
5. Make current API endpoints/contracts, source-of-truth docs, and user guidance match actual implementation. Do not claim "complete" unless both acceptance and regression checks pass. Avoid duplicating old docs or building unrelated RAG, engagement, OAuth rewrites or AWS infrastructure.
6. Use the academy's **existing** Zoom app and the provided backend-only `.env` if already configured. Never print a secret, require it in a prompt, commit it, or send it to CI. Where a real Zoom sandbox is available and expressly permitted, verify early start, recurrence, participant rejoin and final report. If not available, execute full deterministic mock/contract tests and label the real-account proof as blocked.
7. Finish with an unmerged PR to `main` and a concise summary: exact branches/commits/PR URL, changed files, verified commands, passing tests, any unresolved mapping/permission blocker, migration/rollback notes, and the next approved external action (if any). Do not auto-merge or deploy.

## Stop conditions (only legitimate reasons to pause)

- Need Academy/Zoom administrator permission or credentials not already provided through the normal secret configuration.
- Need an explicit decision about ambiguous course mapping, reliable student identity, or attendance policy that changes student outcomes.
- Any live production data mutation, migration, Zoom account change, new billable infrastructure or deployment approval.
- Existing work conflicts or unsafe Git changes that cannot be preserved.

Otherwise implement, test, commit and continue automatically until all WID acceptance criteria are done.
