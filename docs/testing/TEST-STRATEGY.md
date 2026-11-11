# Test strategy and acceptance gates

## Unit tests
Attendance duration union/clip/empty/invalid intervals; authorization policies; token validation; ranking normalization; reaction rate limits; idempotency keys and event ordering.

## PostgreSQL integration tests (Testcontainers)
Flyway schema migration; concurrent insert `ON CONFLICT DO NOTHING` receipt dedup; account creation/invite/refresh rotation transaction; batch enrollment uniqueness; shared-session single attendance; transactionally persisted manual correction; consistent finalization after retries.

## Contract/fake-Zoom tests
Valid and invalid webhook signatures; tampered payload, stale timestamp, replay, duplicated event ID, paginated reports, out-of-order join/leave, empty IDs, 429 retry, report lag and occurrence UUID mapping. Never claim a mock proves actual licensed Zoom permissions.

## Auth security tests
Wrong password, disabled user, reset/reuse, JWT `exp/iss/aud/kid`, algorithm confusion, unauthenticated denied route, student-to-student IDOR, instructor cross-batch, cross-academy, origin/CSRF, cookie flags and sensitive logging.

## UI and E2E
Next.js responsive navigation, keyboard access, login/logout, staff-to-student isolation, filter scope, attendance correction reason, CSV format, read-only provisional vs finalized states, recovery from stale SSE.

## Ops / non-functional
Queue at-least-once stress, worker failures and replay, rate-limit backoff, load/capacity, backup restoration, minimal permissions, availability, monitoring and rollback drills.

**Do not declare production ready on unit tests alone.** See full blueprint for 24 acceptance scenarios and source evidence.
