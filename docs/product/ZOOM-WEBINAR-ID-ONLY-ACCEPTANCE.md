# LUMA: Webinar-ID-only, Zoom-owned schedules (Academy decision)

**Decision date:** 2026-11-18  
**Scope:** Physics Cube Academy's Zoom **Webinar** integration, live attendance, and staff/student screens.  
**Status:** Approved requirement and implementation handoff, **not a claim of implementation**.

## Product contract

1. Staff enters **only an existing Zoom Webinar ID or a supported Zoom webinar link** to monitor. LUMA validates that the connected, academy-authorized Zoom account can read that webinar. Do not ask the user to enter its title, date, start/end time, duration, recurrence, or a class schedule.
2. Zoom is the **sole source of truth** for webinar metadata and scheduling. Read webinar title, agenda when available, timezone, scheduled start/duration, recurrence and occurrences using Zoom **read-only** APIs. Reflect external Zoom schedule/name changes on refresh and eventually in the dashboard. If Zoom supplies no schedule, display "No Zoom schedule"; never fabricate one.
3. LUMA has **no create/edit/reschedule/cancel Zoom webinar actions** and no LUMA-owned class/weekly-series scheduling forms. It may display a read-only calendar/timetable derived from Zoom, plus an "Add webinar ID/link" and "Stop monitoring" control. "Stop monitoring" changes only LUMA's binding, never the Zoom webinar.
4. **Actual** `webinar.started`, not Zoom's scheduled start and not a LUMA clock, triggers a new LUMA attendance run. A host starting at 16:00 for an 18:00 scheduled webinar must yield an actual start of 16:00, with attendance collection at 16:00. A webinar with no schedule must work too.
5. The backend listens whether or not anyone has LUMA open. The dashboard reads persisted state and shows `LIVE` on a started webinar, with connected attendees, known enrolled/absent-from-live counts where mapping is reliable, actual elapsed time, student connection durations and join/rejoin details. Live values are explicitly **provisional**.
6. `webinar.ended` closes the run and schedules existing official Zoom report reconciliation; final attendance uses the existing academy-approved rule, audit, correction and authorization controls. Preserve existing report latency/retry behavior.
7. One saved **webinar ID** can be started on several dates. Persist each actual run against its **webinar occurrence UUID**, never treating the ID as a unique attendance run. Repeated starts, duplicate/out-of-order webhooks and temporary shutdowns may not create duplicate class runs or double-count time.
8. A student's rejoin creates another visit. Compute **total connected duration as the union of connection intervals**, bounded by the **actual** webinar run start/end, not a stale scheduled class window. An 18:00 scheduled webinar actually running 16:00-17:30 must not yield zero minutes due to schedule clipping. Distinguish connection time from attention.
9. Academy membership is read **only** from the approved `public.payment` and `public.student` columns via the existing read-only connection. Do not read/write its attendance tables or mutate its database. Match webinar participants to students using trusted registration/email identity and existing audited review, **never free-text display names alone**. If the Zoom title does not unambiguously identify an Academy `class_type`, do not guess the course or mark students absent; flag the unresolved association for explicit academy resolution.
10. Keep the same Academy-owned Zoom account, existing license, and credential management. Configure the necessary webhook event subscriptions and API read scopes; do not change Zoom schedules or create a new Zoom account. Retain secure staff access and student-data isolation while removing scheduling UX; this request is **not** authorization to remove security controls.

## Core acceptance examples

| Scenario | Expected result |
| --- | --- |
| Add only `12345678901` | Validate authorized webinar; show title and Zoom schedule without asking for date/time |
| Add supported Zoom join link | Extract/validate ID, fetch the same metadata; reject unsupported links clearly |
| Zoom changes 18:00 to 19:00 | Read-only LUMA display reflects Zoom after refresh; no LUMA save required |
| Host starts at 16:00 (scheduled 18:00) | New `LIVE` run at 16:00; participant data counts immediately, independent of timetable |
| Host starts an unscheduled webinar | Same automatic run creation and live attendance |
| Host ends and reopens same webinar ID next day | Second UUID and second, separate attendance run; no cross-run mixing |
| Student joins 16:05-16:40 and 16:45-17:30 | 80 minutes connected, 2 visits, no double count |
| Duplicate/late joined, left, started, ended webhook | Idempotent deterministic final state and unchanged count |
| LUMA browser closed when webinar starts | Backend still ingests; opening LUMA later shows live run and elapsed attendance |
| Unknown/ambiguous student or course | Review state, no false present/absent decision or unauthorized data disclosure |
| Lost webhook / late Zoom report | Detect discrepancy, retry/reconcile; clearly label provisional vs final |
| Non-admin attempts to configure a binding | Denied; existing scoped auth and audit remain intact |
| User visits staff/student screens | No LUMA manual schedule, title edit or webinar-modification control exists |

## Out of scope / non-goals

- Launching a Zoom webinar, changing its recurrence, time, title, registrants, host, or password from LUMA.
- Recording webinar **video/audio**. "Record" in this requirement means attendance/connection evidence only.
- Assuming a calendar event equals a live session, or a Zoom display name equals an Academy student.
- A rewrite of authentication, RAG, unrelated engagement features, or the Academy's existing database.

## External acceptance prerequisites

The academy must grant the existing Zoom app Webinar **read** scopes and verified webhook subscriptions (`webinar.started`, `webinar.ended`, `webinar.participant_joined`, `webinar.participant_left`) and provide a publicly reachable HTTPS webhook endpoint for a real sandbox proof. Where registration identity or course matching is ambiguous, explicitly record the product decision before enabling production attendance. No automatic production deployment or change to a real Zoom webinar is authorized by this document.

## Primary API references (verify current contracts while coding)

- Zoom Get a Webinar: https://developers.zoom.us/docs/api/meetings/ (`GET /webinars/{webinarId}`)
- Zoom List Webinars: https://developers.zoom.us/docs/api/meetings/ (`GET /users/{userId}/webinars`)
- Zoom webinar events: https://developers.zoom.us/docs/api/meetings/events/
- Zoom webhook signature and URL validation: https://developers.zoom.us/docs/api/webhooks/
