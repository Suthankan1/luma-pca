# Academy production pilot plan (slice 007B)

LUMA is built and tested; the pilot is the academy's decision and sign-off. This plan makes it gradual and reversible.
Evidence for each gate is in `GO-LIVE-CHECKLIST.md`.

## Phase 0 — Decisions (academy, before any real student data)
Complete `SIGN-OFF.md`: privacy and consent (ages, retention, deletion), attendance rule, registration policy, AWS account
and budget, domain, RPO/RTO, on-call contacts. **Without these, no real student data is collected** (OPEN-QUESTIONS P0).

## Phase 1 — Staging with test data (1 week)
- Deploy staging (`runbooks/DEPLOY.md`); restore drill on staging; load test at 600 concurrent users (`load-tests/`, PERF-1).
- Run `UAT-SCRIPT.md` with academy staff using test students and the test Zoom webinar. Record results in the script.
- **Exit:** every UAT case passed or accepted with a written exception; no open SEV1/SEV2; load test within targets.

## Phase 2 — One batch (2 weeks)
- Production with one batch whose students (or guardians) have agreed; Zoom webhook allow-list set to that batch's
  webinar; engagement off unless consent is approved.
- Attendance is finalised by staff each class from Zoom's report; staff compare with their usual register for every class.
- **Watch daily:** alarms, Zoom review (unmatched participants, failed events), appeals, differences from the register.
- **Exit:** two weeks with no unexplained difference from the register after review; unmatched participants decided;
  no data-protection issue.

## Phase 3 — All batches
- Clear the webinar allow-list (or list all class webinars); import remaining students; send invitations in batches.
- Keep comparing with the register for the first two weeks of each new batch.

## Rollback (any phase)
Fast and reversible switches, in order of impact (details in `runbooks/DEPLOY.md`):
1. Engagement off (Attendance page) — stops recording reactions and questions.
2. `LUMA_ZOOM_REPORTS_ENABLED=false` / `LUMA_ZOOM_PROCESSING_ENABLED=false` — attendance stays as last processed; receipts
   are still stored and can be processed later.
3. Remove the Zoom webhook secret — the endpoint answers 404; Zoom retries for a while, then the subscription can be paused.
4. Roll back the release (previous images) or restore the pre-release snapshot.
Paper registers stay the record of truth until Phase 2 exits.

## Roles
Academy owner: decisions, sign-off, attendance rule, engagement switch. Administrators: data, Zoom review, finalising,
appeals. Engineering lead: deployment, alarms, incidents. Both owner and engineering lead sign each phase exit.
