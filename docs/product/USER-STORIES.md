# Priority user stories — acceptance outlines

## Identity and operations
- US-001 As academy owner, I can invite a staff user with role and assigned scope; unaffiliated users cannot access admin functions.
- US-002 As a student, I can activate and sign in safely, access only my profile/attendance, and log out on all devices.
- US-003 As admin, I can add/edit courses and batches and enroll one student into multiple batches without creating duplicate students.
- US-004 As instructor, I can read only class sessions and rosters assigned to my course/batch scope.
- US-005 As staff, I can schedule one class for multiple batches; a shared student generates one attendance record.

## Zoom and attendance
- US-006 As admin, I can authorize the academy-owned Zoom integration and see scopes/connectivity without exposing credentials.
- US-007 As system, I verify webhook signatures and timestamps, record exactly one logical receipt and reject invalid events.
- US-008 As attendance officer, I see provisional join/leave timelines, including reconnects, without overstating certainty.
- US-009 As system, I reconcile post-webinar reports, merge overlapping intervals, and flag mismatches rather than silently deciding.
- US-010 As staff, I can correct a record with recorded reason, role restriction and full audit history.
- US-011 As student, I can request review of my record and see final decision.

## Engagement and reporting
- US-012 As student, I may submit portal reactions and pulse answers within rate limits and privacy notice.
- US-013 As instructor, I can configure and approve learning checks without assuming they can be auto-launched inside Zoom.
- US-014 As analyst, I can produce course/batch-scoped attendance reports and distinct participation sources.
- US-015 As academy admin, I can view Top 10/100 *eligible consented* activity by fair, disclosed criteria.

Each story must be split into an independent, tested vertical slice; see implementation plan. Cross-scope negative tests are mandatory for all private endpoints.
