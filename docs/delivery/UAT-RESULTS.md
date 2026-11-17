# User acceptance test — developer run (17 Nov 2026)

The 17 cases of [UAT-SCRIPT.md](UAT-SCRIPT.md), run by the development team before handover, on the **real local stack**
(PostgreSQL 17, the Spring Boot API jar, the Next.js production server, a real SMTP catcher, a real Chrome) and, where a
case needs Zoom, against the academy's **real Zoom account**. Test data only; no real students.

This is not the academy's acceptance: Phase 1 of [PILOT-PLAN.md](PILOT-PLAN.md) repeats the script with academy staff on
staging and records their initials in UAT-SCRIPT.md.

How to repeat: `scripts/staff-ui-real-stack.sh` (cases 1–5, 9–15, 17), `scripts/zoom-api-check.sh` (6),
`scripts/db-restore-drill.sh` (16). The same real-stack run is the `real-stack` CI workflow on every pull request.

| # | Case | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Owner invites an instructor; instructor sees only their batches | **Pass** | Real-stack test 1, steps 7–9: invitation emailed by SMTP, MFA enrolled, roster of the assigned batch only; removed assignment hides it on the next request |
| 2 | Student activates, signs in, sees own data, signs out | **Pass** | Real-stack test 1, steps 5, 7b′, 10: activation, portal with own classes and batch, password reset by email (single use) |
| 3 | Course, two batches, CSV import with refusal of bad rows, second enrolment | **Pass** | Real-stack test 1, steps 3, 6b, 12: bad rows refused with line numbers, 2 imported; PCA-REAL-001 enrolled in MON and PAST (one record, two enrolments) |
| 4 | Instructor sees only assigned batches' classes | **Pass** | Real-stack test 1, step 8–9 |
| 5 | Weekly series for batches with the webinar ID | **Pass** | Real-stack test 1, step 7b″ (series entered in Sri Lanka time, stored UTC) |
| 6 | `zoom-api-check.sh` with the academy's Zoom app | **Pass** | 17 Nov: 5 read scopes granted; webinar 838 3442 8148 read; 3 approved registrants; 1 past run, 5 join sessions by 4 people, all with email; no credentials, names or emails printed |
| 7 | Unsigned webhook refused | **Pass** | `ZoomWebhookHttpIntegrationTest` (401, nothing stored), CI on every backend change; with real Zoom on 13 Nov, signed deliveries were accepted (12 events, each stored once) |
| 8 | Live webinar, 3 attendees, one rejoins: people matched, provisional attendance merged | **Pass** | Real Zoom, 13 Nov (ZOOM-TEST-WEBINAR.md): host + 3 attendees, one rejoined; 5 joins/5 leaves stored once, matched by email; real-stack test 1, step 12 repeats matching and merging on the real API |
| 9 | Fetch Zoom's report and finalise by the rule | **Pass** | Real-stack test 1, step 12: rule 75 % / 10 min recorded; 1 h 49 min of 2 h, first join 18:06 → Present |
| 10 | Correct to Excused with a reason | **Pass** | Real-stack test 1, step 12: status Excused, history "corrected from present", activity log entry, email "Your attendance was updated" by SMTP |
| 11 | Student appeals; admin accepts | **Pass** | Real-stack test 2: appeal sent from the portal, accepted as Present with a note; the student sees Present and "appeal accepted" |
| 12 | Engagement on; consent; reaction; second within 10 s refused | **Pass** | Real-stack test 2: owner switched it on; student agreed (named in rankings); "I understand" counted (1) on the staff class page; "Slow down" straight after refused "Please wait a few seconds between reactions" |
| 13 | Question for 3 minutes, answered once, closed early, counts shown | **Pass** | Real-stack test 2: student answered "Newton" (form gone after answering); staff closed early; counts Newton 1, Joule 0 |
| 14 | Class CSV and batch CSV | **Pass** | Real-stack test 1, step 12 (class CSV agrees with the screen: EXCUSED); batch CSV in `attendance-export.spec` (mock) and `AttendanceExportIntegrationTest` |
| 15 | Batch top 10: consenting only, unnamed without opt-in | **Pass** | Real-stack test 2: one ranked student, named because they opted in; unnamed case in `EngagementIntegrationTest` |
| 16 | Restore drill | **Pass** | 17 Nov on a freshly migrated and seeded database: 25 migrations identical (latest V25), 37 tables and 30 rows match, the restored audit log refused an update |
| 17 | Cases 2, 8 and 11 on a phone | **Pass** | Real-stack test 2 (Pixel 7): student portal, student class page, Zoom review, Attendance (appeals) and the staff class page have no sideways scrolling; the whole mock suite also runs on the phone project (106 tests) |

**Result: 17 of 17 passed.** Accessibility: axe finds no serious or critical WCAG 2.1 A/AA issue on the public, staff and
student pages (`accessibility.spec`, both projects).
