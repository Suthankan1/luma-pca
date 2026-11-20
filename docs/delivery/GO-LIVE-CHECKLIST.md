# Production go-live checklist — release gate

**None of these are pre-approved.** Academy owner and engineering lead must sign off before connecting real student records
(`SIGN-OFF.md`, `PILOT-PLAN.md`). Status as of 17 Nov 2026: **built** = done in the software with automated tests;
**academy** = needs the academy's decision or action.

| # | Gate | Status | Evidence / what remains |
| --- | --- | --- | --- |
| 1 | Academy approves identity policy, attendance thresholds, manual review, retention, reporting and leaderboard consent | **academy** | Software supports each (owner-recorded rule, corrections, appeals, consent, staff-only rankings); decisions in `SIGN-OFF.md` |
| 2 | Zoom license/scopes and join/leave/rejoin/register/report proof documented | **built**; one gap | Test webinar 13 Nov 2026: 12 events once each, rejoin, 3 registrants, API report "5 join sessions by 4 people" matched (`docs/integration/ZOOM-TEST-WEBINAR.md`). Gap: a renamed guest was not tried |
| 3 | Account provisioning, email and password sign-in for every role (MFA removed 2026-11-20, owner decision), password change, signing keys, rotation, reset, token reuse/revocation, CSRF | **built** | `Auth*`, `RefreshSession*`, `PasswordReset*`, `SigningKeysTest`; rotation procedure in `runbooks/INCIDENT-RESPONSE.md` |
| 4 | Student self-only and cross-academy/course/batch IDOR tests pass | **built** | Every endpoint test includes other-academy and out-of-scope cases (e.g. `Attendance*`, `Engagement*`, `ClassSession*`) |
| 5 | Webhook signatures, replay protection, durable receipts, worker idempotency | **built** | `ZoomWebhookSignatureTest`, `ZoomWebhookHttpIntegrationTest`, `ZoomReceiptProcessingIntegrationTest`; real webinar delivered once each |
| 6 | Interval math, recurring occurrences, rejoin, pagination, post-webinar reports match accepted baseline | **built**; baseline is the academy's | `AttendanceDurationCalculatorTest`, golden dataset `attendance/golden-outcomes.csv`, `ZoomReportIntegrationTest`; accepted baseline = Phase 2 register comparison |
| 7 | No Zoom emoji attribution assumed; portal consent and anti-spam | **built** | Engagement is portal-only, opt-in, rate-limited (`EngagementIntegrationTest`) |
| 8 | Load/capacity and Zoom rate limits for real volumes | **academy** (staging needed) | Load-test tooling `load-tests/` (PERF-1); run against staging at 600 concurrent users in Phase 1 |
| 9 | TLS, secrets, least-privilege IAM, private RDS/S3, encrypted backups, alerts, audit logs deployed | **built as code**; not deployed | `infra/terraform` validated in CI; audit log append-only; deploying needs budget and account |
| 10 | Backup restoration drill succeeds; incident response, rollback, on-call documented | **built**; repeat on staging | `scripts/db-restore-drill.sh`, `BackupRestoreIntegrationTest`, `runbooks/RESTORE.md`, `DEPLOY.md`, `INCIDENT-RESPONSE.md` |
| 11 | Privacy/legal review (minors, retention, deletion) | **built**; policy is the academy's | Academy answered 15 Nov: age is not a problem, attendance data only, guardians agree. Built: no IP/device data (PRIV-1), owner-only erasure on request, nightly retention (`PrivacyIntegrationTest`) |
| 12 | Academy UAT and support/handover signed off; gradual pilot with exception monitoring | **developer run done**; academy run pending | Developer UAT 17/17 (`UAT-RESULTS.md`), pilot rehearsal 189/189 records match the register (`PILOT-SIMULATION.md`); academy repeats UAT on staging and runs the real pilot (`PILOT-PLAN.md`); `HANDOVER.md` |
