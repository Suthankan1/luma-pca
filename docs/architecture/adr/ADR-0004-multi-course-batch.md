# ADR-0004 — Course/batch as day-one domain primitives
**Status:** Accepted · 2026-11-11
**Decision:** Course has batches. Students are single academy identities connected via batch enrollments (many-to-many). Class sessions are linked to one or multiple batches (`session_batches`) so shared lessons are not duplicated. Staff assignments have academy/course/batch scope, verified for every API resource. Attendance unique by `(class_session_id, student_id)`; eligible class membership is resolved at session time.
**Rejected:** User account per batch, one webinar ID as a session identity, blindly using Zoom free-text display names.
**Consequences:** Sophisticated joins, enrollment date validation and cross-batch authorization tests are required.
