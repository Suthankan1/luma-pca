# Product requirements document — LUMA / Physics Cube Academy

## Goal
Replace manual roll call in Zoom Webinars with secure, auditable **connected-duration attendance** tied to actual academy student IDs and course/batch membership. Provide actionable attendance reviews, staff management reports and optional measurable student engagement without making false claims about attention.

## Confirmed needs
Academy has multiple courses and batches, uses Zoom Webinars, staff currently manually call student names at start/end, wants monthly attendance/engagement and top 10/top 100 participation reports, and plans a live deployment. The production backend choice is Spring Boot; both students and staff need first-party JWT authentication.

## Users
Academy owner/admin; attendance officer; instructor assigned to batches; analyst (optional); student; system operator. Privacy-sensitive student dashboards require own-record-only access and approved ranking visibility.

## In-scope release R1
1. Secure invite/account activation, login, recovery, role/scope enforcement, session revocation and MFA for admins.
2. Courses, batches, students, enrollments, staff assignments and class sessions (shared sessions allowed).
3. Academy-owned authorized Zoom account integration, participant identity registration/mapping and occurrence identity.
4. Signed webhook capture and replay handling; provisional live dashboard; report reconciliation, attendance rules and correction workflow.
5. Student own attendance history, course/batch views, scoped monthly CSV exports, audit trail, observability, backups and operator runbooks.

## R2 engagement and analytics
6. Q&A/poll imports where Zoom permissions and data make attribution reliable.
7. Optional academy student portal reactions, rate limits, learning pulse checks (not native Zoom poll auto-launch).
8. Monthly Top 10/Top 100 separate metrics, per-class/per-hour normalized participation, opt-in/visibility controls.
9. PDF/CSV reporting, staff notifications, operational dashboards.

## Future possible enhancements
Payments and fees, learning management/assignments, parent portal, multiple branches/academies, native apps and other meeting platforms. Do not treat these as already built.

## Core system integrity requirements
- Academy/course/batch access enforced server-side.
- Student uniqueness independent of enrollments; shared class not doubled.
- Attendance never inferred from login or untrusted display names.
- Online status is provisional; final status requires reconciliation.
- Corrections require authorized actor, reason, old/new value and audit timestamp.
- Native Zoom attendee emoji attribution remains unproven.
- Real PII privacy obligations and student consent must be documented before onboarding.

## Success criteria
- Demo course/batch enrollment and authorized staff/student data access pass tests.
- Duplicate/out-of-order/lost Zoom events produce correct or reviewable session evidence.
- Reconciling a completed webinar matches accepted ground-truth datasets.
- No cross-student or cross-batch private data exposure.
- Production monitoring, backup restore, incident drills and academy sign-off completed before go-live.

## Out-of-scope assumptions
No assumed student numbers, licensing, registration constraints, automatic native Zoom poll launch, attendance-as-attention score, unrestricted native Zoom reactions, live production AWS environment or approved hosting budget. See [questions](OPEN-QUESTIONS.md).
