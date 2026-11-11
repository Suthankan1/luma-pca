# System architecture — authoritative overview

**Status:** Decided architecture; implementation is only an initial scaffold. For the 10,000+ word full specification and research sources, see [the full production blueprint](full-production-blueprint.md). Use [ADRs](adr/) for changed decisions.

## System context

- **Client:** Next.js 16 / TypeScript responsive browser interface; staff desktop and student mobile/PWA views.
- **BFF:** Next.js server-side endpoints and HttpOnly cookies manage sessions; never expose refresh or access tokens to browser JavaScript.
- **Backend:** Java 21, Spring Boot 4.1.2, **one modular monolith** with API runtime and future separately scaled worker runtime sharing feature code.
- **Data:** PostgreSQL/Flyway for academy, courses, batches, people, scoped roles, webinar occurrences, attendance intervals, receipts, engagement, audit and reports.
- **Zoom:** academy-owned server-to-server OAuth for authorized calls, signed HTTPS webhook ingress, report fetching and post-webinar reconciliation.
- **Async:** PostgreSQL transactional receipts/inbox/outbox, SQS + dead-letter queue, idempotent handler processing and replay tools. This is **planned**; only local PostgreSQL runs today.
- **Streaming:** SSE for provisional staff live presence, not an attendance source of truth.
- **Infrastructure:** propose AWS ECS/Fargate, RDS, SQS, private S3, monitoring and Terraform subject to academy budget and account details.

## Module map

| Package | Owns | Never does |
| --- | --- | --- |
| `identity` | Account lifecycle, password policies, session rotation, JWT | Assume a role implies every batch |
| `academy` | Org settings, registration and academy policy | Treat future multi-tenant as currently delivered |
| `courses`, `batches`, `enrollments` | Enrollments, memberships and staff assignments | Copy the same student per batch |
| `webinars`, `zoom` | Class-occurrence mapping, authorized REST calls, webhook receipts | Infer native reaction identities |
| `attendance` | Interval union, overrides, reconciliation and final records | Conflate connected time and attention |
| `engagement` | LUMA portal reactions/pulse checks and attributable Zoom Q&A/polls | Rank unverifiable native webinar emojis |
| `analytics`, `reports` | Scoped aggregations, exports, fair ranking rates | Read all student data without authorization |
| `operations`, `shared` | Outbox worker, SQS, safe observability, shared primitives | Turn cross-module entity access into the default |

## Domain identity invariants

`Academy -> Course -> Batch`; `Student <-> Batch` through `Enrollment`; `ClassSession <-> Batch` through `SessionBatch`. Each class session can serve multiple batches without duplicate attendance for the same student. Each `AttendanceRecord` is uniquely `(class_session_id, student_id)`. Separate Zoom webinar scheduled IDs and instance UUIDs for recurring webinars. Student identity is the academy's stable student ID, never a mutable Zoom display name.

## Security design

Students and staff receive first-party short-lived signed JWT **access** tokens after authenticated login. Refresh tokens are random opaque secrets, hashed in DB with rotation and reuse-detection. Roles are assigned at academy/course/batch scope; both role checks and object-level authorization are required on every private operation. Zoom OAuth credentials are **different secrets** unrelated to student JWTs. See [JWT auth](../security/JWT-AUTH.md), [permissions](../security/AUTHORIZATION-MATRIX.md) and [threat model](../security/THREAT-MODEL.md).

## Critical status map

- **Implemented in starter:** basic Next app previews, Java Boot REST bootstrap endpoint, Spring default-deny API security, Flyway V1 academy/course tables, deterministic connection interval union with unit tests, Docker PostgreSQL, persistent accounts with academy-level roles and Argon2id password hashing and single-use activation invitations (service layer only, no HTTP route), Next.js BFF session cookies tested against a mock API.
- **Implemented since:** password sign-in issuing RS256 access tokens, per-request account/auth-version checks, lockout, invitation activation route, public JWKS.
- **Not implemented:** refresh sessions, MFA, recovery, audit trail, first-account bootstrap, real user data, all course/batch CRUD, Zoom webhooks/oauth, attendance persistence/reconciliation, worker/SQS, SSE, reports and production infrastructure.
- **Requires feasibility proof:** Zoom license and scopes; unique student registrations; recurring webinar UUID behavior; Q&A/poll data; native attendee emoji identity. See [Zoom rules](../integration/ZOOM-RULES.md).

## Diagrams and contracts

[Context](../diagrams/context.mmd), [domain ERD](../diagrams/domain-erd.mmd), [Zoom sequence](../diagrams/zoom-events.mmd), [auth flow](../diagrams/auth-flow.mmd), [deployment](../diagrams/deployment.mmd); [OpenAPI planning contract](../../contracts/openapi/platform-v1.yaml). Diagram links refer to editable Mermaid source, not validated deployed infrastructure.
