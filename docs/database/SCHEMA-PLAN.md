# Database model and migration sequencing

**Present:** V1 `academies` and `courses`; V2 `accounts` and `account_roles` (academy-level role grants only); V3 `account_invitations`; V4 `refresh_sessions` and `refresh_tokens`. All other tables are **planned**: design is in the full blueprint. Do not infer migrations are already applied.

| Group | Planned tables | Main constraints |
| --- | --- | --- |
| Auth | accounts, account_roles/scopes, refresh_sessions, invitations, recovery_tokens | globally unique normalized email (implemented in V2; stricter than academy+email so email-only sign-in is unambiguous), token hash uniqueness, session family revoke |
| Academy | academies, courses, batches, students, enrollments, staff_assignments | one student identity; effective membership dates; course→batch |
| Timetable | class_sessions, session_batches, webinar_occurrences, webinar_registrations | session:batch many-to-many; Zoom instance UUID separate; no duplicate shared attendance |
| Attendance | attendance_intervals, attendance_records, attendance_corrections | `UNIQUE(class_session_id, student_id)`; intervals overlap/merge; reasoned manual overrides |
| Webhook/async | webhook_receipts, outbox_events, processed_event_receipts, dead_letter_reviews | canonical event identity, idempotence, payload retention controls |
| Engagement | portal_reactions, qna_events, poll_results, pulse_questions, pulse_responses | source/provenance, consent, rate-limit and enrollment rules |
| Reporting | report_jobs, export_artifacts, audit_entries | scoped exports, private links, actor traceability |

**Time representation:** UTC TIMESTAMPTZ in database, class timezone as IANA zone (`Asia/Colombo` for academy default), display localized. No assumed student attendance from desktop time zone.

**Migration policy:** One immutable reviewed Flyway migration per feature slice; baseline and constraints first, domain application code second; add indexes based on queries, unique keys on receipts and shared-session attendance. Never edit an applied production migration. Integration-test migrations against PostgreSQL via Testcontainers before release.

**PII:** Record hashes and encrypted sensitive data appropriately, design retention/deletion lifecycle, never log raw Zoom token/payload in application logs, and keep analytical exports private.
