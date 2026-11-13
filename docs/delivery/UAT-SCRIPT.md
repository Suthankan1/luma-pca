# User acceptance test script (Phase 1, staging, test data only)

Run with academy staff. Record **Pass / Fail / Exception (with note)** and initials. Each case maps to a user story
(docs/product/USER-STORIES.md) and to an automated test that already covers it.

| # | Story | As | Steps | Expected | Automated cover | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | US-001 | Owner | Staff → add an instructor with an email invitation; open the link; set a password | The instructor signs in and sees only Overview, Classes and their batches | `staff-team.spec`, `StaffAccount*IntegrationTest` | |
| 2 | US-002 | Student | Activate from the invitation, sign in, open the portal, sign out everywhere | Only own classes, batches, attendance | `student-portal.spec`, `activation.spec` | |
| 3 | US-003 | Admin | Create a course and two batches; import 10 students from CSV into one; enrol one also in the other batch | One student record, two enrolments; import refuses bad rows with row numbers | `student-import.spec`, `StudentImport*Test` | |
| 4 | US-004 | Instructor | Open Classes and a batch not assigned | Only assigned batches' classes; the other batch is not found | `ClassSessionHttpIntegrationTest` | |
| 5 | US-005 | Admin | Schedule a weekly series for both batches with the test webinar ID | Each student expected once per class | `class-series.spec`, `ClassSeries*Test` | |
| 6 | US-006 | Engineer | `scripts/zoom-api-check.sh` with the academy's Zoom app | Scopes listed, webinar read, no credentials printed | `ZoomApiClientTest` | |
| 7 | US-007 | Engineer | Send an unsigned POST to the webhook URL | 401; nothing stored | `ZoomWebhookHttpIntegrationTest` | |
| 8 | US-008 | Admin | Run the test webinar with 3 test attendees (one rejoins); open Zoom review → People, then the class page | People matched by email; provisional attendance with rejoins merged and lateness | `ZoomParticipantIntegrationTest`, `ProvisionalAttendanceIntegrationTest` | |
| 9 | US-009 | Admin | After the webinar: fetch Zoom's report; finalise the class (owner has set the rule) | Status changes to final; outcomes follow the rule | `ZoomReportIntegrationTest`, `AttendanceFinalisationIntegrationTest`, golden dataset | |
| 10 | US-010 | Attendance officer | Correct one student to Excused with a reason | Outcome changes; history shows the original; activity log entry | `AttendanceCorrectionIntegrationTest`, `attendance-rule.spec` | |
| 11 | US-011 | Student → Admin | Student appeals; admin accepts with a note | Student sees the new outcome and "appeal accepted" | `attendance-appeals.spec` | |
| 12 | US-012 | Owner → Student | Switch engagement on; student agrees, reacts during a class | Reaction counted; a second within 10 s is refused | `EngagementIntegrationTest`, `engagement.spec` | |
| 13 | US-013 | Instructor | Open a question for 3 minutes; close early | Students answer once; counts shown | `EngagementIntegrationTest` | |
| 14 | US-014 | Admin | Download a class CSV and a batch CSV for a month | Same facts as the screens; opens safely in a spreadsheet; export logged | `attendance-export.spec`, `AttendanceExportIntegrationTest` | |
| 15 | US-015 | Admin | Batch → Engagement ranking, top 10 | Only consenting students; unnamed without opt-in; method shown | `EngagementIntegrationTest` | |
| 16 | — | Engineer | Restore drill on staging (`runbooks/RESTORE.md`) | Same migrations and row counts; audit log still append-only | `BackupRestoreIntegrationTest` | |
| 17 | — | Everyone | Repeat 2, 8 and 11 on a phone | Usable without sideways scrolling | Playwright `mobile` project | |
