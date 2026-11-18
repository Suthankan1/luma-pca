# Implementation and verification plan: ID-only Zoom Webinar monitoring

**Target:** LUMA remains a read-only consumer of Zoom scheduling. A webinar's **actual** start automatically creates a trackable attendance session even when no LUMA schedule exists or Zoom runs early/late. Never touch the Academy's attendance table.

Each slice is small, tested and separately committed. Complete the entire sequence autonomously if permitted; pause only for missing authority, missing credentials, an unresolved Academy business decision or an unsafe production operation.

| Slice | Code work | Required evidence / exit criterion |
| --- | --- | --- |
| WID-00 Audit | Inspect actual `main`, current branches, active PRs, `AGENTS.md`, Zoom and attendance domain, migrations and test harness; record invariant map | No premature implementation claim; baseline tests and affected files identified |
| WID-01 Binding | Owner/admin ID/link-only monitoring endpoint, strict input parsing, Zoom account ownership/read verification, LUMA-only binding/migration | Valid ID/link succeeds; invalid/foreign/unreadable ID rejected; read scopes only, no Zoom update call |
| WID-02 Metadata | Read-only Zoom webinar details, occurrences/schedule caching and refresh; expose title, timezone, schedule and `lastSynced` | Zoom title/time change propagates; unscheduled response doesn't invent time; API outage shows stale/unknown |
| WID-03 Start | Adapt durable webhook worker so `webinar.started` upserts run by Zoom UUID without any scheduled class prerequisite | 16:00 start for 18:00 schedule yields LIVE at 16:00; no schedule yields LIVE; replay one run |
| WID-04 Visits | Attach participant join/leave to run UUID, handle early/out-of-order events, reconnects, duplicate deliveries | 35 + 45 = 80 min across two visits, no double-count, distinct UUIDs stay separate |
| WID-05 Finalization | Close on `webinar.ended`; restore/preserve existing report queue, UUID reconciliation, delayed reporting, academy rule and audit | Correct final report even after missed webhook/retry; never finalize unknown student/course as absent |
| WID-06 Academy | Use existing `public.payment`/`public.student` read-only catalog, match known course/student identity, surface unresolved links | Distinct student count only; no financial columns or Academy attendance access; no name-only identity matching |
| WID-07 UI | Replace schedule/edit/weekly-series/import forms with webinar-ID/link-only monitor and Zoom-owned read-only timetable, live/current/history/attendance views | No LUMA schedule/title/time editing; status auto-refreshes if browser opens during webinar; mobile works |
| WID-08 Regression | Run Java integration suite/Testcontainers, Next tests/lint/build/Playwright, security/contract/docs checks; real Zoom sandbox when authorized | Evidence-based checklist below; no production/live data write; docs/checkpoint reflect actual verified state |

## Non-negotiable test matrix

1. **Timing:** scheduled 18:00, starts 16:00; starts late; no scheduled occurrence; schedule changes in Zoom without LUMA edits; timezone and DST; webinar starts when web app is closed.
2. **Instance identity:** same numeric webinar ID over 3 dates = 3 distinct UUID-based sessions; webhook duplicate/out-of-order/replay; restart and missed start/end; no cross-academy mixing.
3. **Attendance:** reconnect; duplicate overlap; open visit; webinar ends before participant_left; real actual-run interval not scheduled time; report delayed/fails/retries; provisional vs final visibly different.
4. **Identity:** participant email absent; renamed/guest; common/shared email; missing Academy `pcaid`; unmatched course; ambiguous Zoom title. Never make an ungrounded presence/absence decision.
5. **Security:** non-admin cannot add monitor; another academy's webinar/roster invisible; student sees only own attendance; webhook invalid signature/timestamp rejected; no secrets or PII in logs; the Academy DB role cannot write or read unrelated columns/tables.
6. **UI/API:** only ID/link input, no schedule/name controls, explicit Zoom-origin scheduled metadata + actual timing, live status, stale refresh state, user-friendly errors, accessibility, responsive layout.
7. **Preservation:** existing official-report finalization, audit, correction workflow, staff/student logins, Academy roster syncing and historical attendance still work; removal of scheduling UI does not delete historical records.

## Verification and delivery rules

- Use actual repository scripts; at minimum attempt `mvn -f backend/pom.xml test`, `npm --prefix frontend run lint`, `npm --prefix frontend run build`, and the appropriate Playwright/real-stack commands documented in README. Report exactly what passed, failed or was blocked; never fake a live Zoom success.
- Add tests to existing test suites, use Testcontainers only if Docker is available, mock Zoom at service boundaries for CI, and run an authorized test Webinar for the end-to-end acceptance proof.
- Introduce versioned forward-only Flyway migrations; never rework applied migrations or copy Academy raw data into fixtures. Preserve existing secrets/configuration.
- Incrementally update the relevant OpenAPI contracts, source-of-truth architecture, `docs/integration/ZOOM-RULES.md`, UI help and `docs/ai/checkpoints/PROGRESS.md`; update README only when final verified functionality is stable.
- Each verified slice gets its own commit; publish a feature PR to `main` for review **without merging**. A deployment may be triggered by merge, so production promotion remains explicitly out of scope.
- If missing Zoom app read scopes or webhook subscription access prevents live verification, finish safe testable local work and disclose the precise external blocker. Do not work around authorization or mark the pilot complete.

## Acceptance gate

The implementation is not done until a saved webinar ID automatically yields a distinct LIVE attendance run on actual Zoom start, a correct reconciled final report on Zoom end, and **zero in-LUMA webinar scheduling/editing actions**, with preserved authorization and tests for early, unscheduled and recurring webinar starts.
