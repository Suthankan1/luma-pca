# System architecture — authoritative overview

**Status (17 Nov 2026):** built and tested end to end for one academy; not yet deployed. The checkpoint
([PROGRESS.md](../ai/checkpoints/PROGRESS.md)) holds the evidence for every slice. The
[full production blueprint](full-production-blueprint.md) is the original proposal; where it differs from this page, this
page describes what exists. [ADRs](adr/) record decisions.

## System context

- **Browser:** Next.js 16 / TypeScript, staff desktop pages and a mobile-first student portal. Browsers talk only to the
  Next.js app.
- **BFF:** Next.js server actions and route handlers hold the session in a sealed HttpOnly cookie and call the API from the
  server. Access and refresh tokens never reach browser JavaScript or storage.
- **Backend:** Java 21, Spring Boot 4.1.2, one modular monolith. Background work (Zoom receipts, Zoom reports, invitation
  emails, retention) runs in the same process on polling workers.
- **Data:** PostgreSQL 17 with Flyway migrations (V1–V25). The audit log is append-only (a trigger refuses updates).
- **Zoom:** signed webhooks into a durable inbox; a server-to-server OAuth client (read-only) for webinar details,
  registrants, past occurrences and participant reports.
- **Async:** a database queue (`FOR UPDATE SKIP LOCKED`) with retries, backoff, dead letters and an audited replay. There is
  no message broker; at one academy's volume none is needed.
- **Live updates:** the staff and student class pages refresh themselves while a class runs; there is no server push (SSE).
- **Infrastructure as code, not applied:** AWS ECS Fargate, RDS, S3, WAF, CloudWatch alarms, AWS Backup (Terraform,
  validated in CI). Applying it needs the academy's AWS account and budget.

## Module map (backend packages)

| Package | Owns |
| --- | --- |
| `identity` | Accounts, passwords (Argon2id), lockout, TOTP MFA for all staff, refresh sessions, JWTs, invitations, resets, email notices |
| `courses`, `batches`, `enrollments`, `students` | Academic structure, CSV import, staff assignments to batches |
| `sessions` | Classes and weekly series shared by several batches |
| `zoom` | Webhook ingress and worker, occurrence linking, participant matching (email, registration email, staff decisions), reports, webinar lookup |
| `attendance` | Provisional facts, the academy rule, finalisation and recalculation, corrections, appeals, histories, CSV exports |
| `engagement` | Consent, portal reactions, pulse questions, class / batch / course rankings |
| `audit` | Append-only activity log |
| `operations` | First-owner bootstrap, student erasure, retention job |
| `shared` | Errors, rate limits (per visitor and per account), security configuration |

## Domain identity invariants

`Academy -> Course -> Batch`; `Student <-> Batch` through `Enrollment`; `ClassSession <-> Batch` through `SessionBatch`. A
class serving several batches expects each student once. Each `AttendanceRecord` is unique per `(class_session_id,
student_id)`. A recurring webinar's ID and each occurrence's UUID are kept apart. A student is identified by the academy's
student record, never by a Zoom display name.

## Security design

Short-lived RS256 access tokens bound to a rotating refresh session (reuse detection revokes the session). Every staff
role signs in with TOTP MFA. Roles are scoped to the academy, assigned batches or the student's own records, and checked on
every route with object-level checks. Zoom credentials are separate secrets. See [JWT auth](../security/JWT-AUTH.md),
[permissions](../security/AUTHORIZATION-MATRIX.md) and [threat model](../security/THREAT-MODEL.md).

## Known limits

See [KNOWN-LIMITS.md](../delivery/KNOWN-LIMITS.md): webinars only (not meetings), one academy per deployment, and what
could not be proven without a deployment or more real webinars.

## Diagrams and contracts

[Context](../diagrams/context.mmd), [domain ERD](../diagrams/domain-erd.mmd), [Zoom sequence](../diagrams/zoom-events.mmd),
[auth flow](../diagrams/auth-flow.mmd), [deployment](../diagrams/deployment.mmd); [OpenAPI contract](../../contracts/openapi/platform-v1.yaml).
