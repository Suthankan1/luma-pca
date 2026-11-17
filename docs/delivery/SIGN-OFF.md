# Academy decisions and sign-off

Each line needs the academy's answer and who approved it. LUMA's defaults are shown; nothing here is pre-approved.

| # | Decision | LUMA's default / where it is set | Academy's answer | Approved by, date |
| --- | --- | --- | --- | --- |
| 1 | Are any students under 18? Guardian consent? | — (OPEN-QUESTIONS P0) | Age is not a problem; attendance data only; guardians agree (15 Nov 2026, to be signed) | |
| 2 | Retention: attendance records, Zoom receipts, exports, audit log, backups | Emails removed from Zoom event records after 180 days; exports 30 days; RDS backups 7 days, daily snapshots 35 days; others kept (`luma.retention.*`, Terraform variables) | | |
| 3 | Deletion of a student's data on request | Built: an owner erases the student (attendance kept anonymised, audited) | | |
| 4 | Attendance rule (connected share, lateness) | None — recorded by the owner on the Attendance page | | |
| 5 | Exceptions policy (excused absences, appeals deadline) | Corrections with reasons; appeals any time | | |
| 6 | Zoom registration with Zoom sign-in required for every class webinar | Recommended: it gives each attendee a stable email | | |
| 7 | Engagement (reactions, questions) and rankings | Off; staff-only rankings; students opt in | | |
| 8 | AWS account owner, region, budget | `ap-south-1`; cost drivers in `runbooks/COSTS.md` | | |
| 9 | Domain and mail sender | — | | |
| 10 | RPO / RTO and on-call contacts | Single-AZ, 7-day backups, restore tested | | |
| 11 | Who may export attendance (personal data) | Owners, administrators, assigned staff for their batches | | |

## Phase sign-off

| Phase (PILOT-PLAN) | Owner | Engineering lead | Date | Notes |
| --- | --- | --- | --- | --- |
| 0 Decisions complete | | | | |
| 1 Staging UAT passed | | | | |
| 2 One-batch pilot passed | | | | |
| 3 All batches live | | | | |
