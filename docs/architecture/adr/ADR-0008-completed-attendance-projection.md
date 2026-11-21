# ADR 0008 · Completed attendance projection

## Status

Implemented and verified locally. Hosted activation requires attendance parity and a reviewed rollout.

## Context

The synthetic staff benchmark found repeated Zoom report and identity reads across monthly pages. Batching reduced query fan-out. Reopening an unchanged month still reconstructs the same completed participation facts.

## Decision

- Keep an attendance-owned, rebuildable PostgreSQL projection for completed sessions whose linked occurrences all have fetched reports. Store a session marker and one compact row per matched student with the visit count and connected seconds. The session marker distinguishes a successful projection with zero matched students from missing evidence.
- Read expected rosters and official attendance outcomes from their existing sources. A correction, recalculation, or enrolment change is therefore visible without waiting for this projection. Use fresh evidence for live, missing, or invalidated sessions and for detailed timelines.
- Advance the source revision in the same transaction as changes to report evidence, host evidence, or the session window. Link changes mark both affected classes. An academy identity revision covers email matching and explicit mapping changes. A rebuild publishes only when both revisions still match. Readers check revisions and projected rows together in one statement.
- Claim due work in bounded batches with `FOR UPDATE SKIP LOCKED` and a five-minute lease. Unsupported sessions and failures retry after the lease. Store no Zoom participant emails or names in the projection. Student erasure invalidates the evidence and removes any matching projected row in its transaction.
- Scope both the eligible job and the projected student to the same academy. Use ordinary staff and student API authorization for all reads. Use an additive Flyway migration, no external job queue, and an application flag that defaults to disabled until verified.

## Acceptance and rollback

Local tests compare projected and source-backed results, cover academy isolation, reject an obsolete lease after report replacement and identity changes, and verify empty reports and erasure. Hosted rollout still needs job lag, published-read latency and academy parity measurements. Setting `LUMA_ATTENDANCE_PROJECTION_ENABLED=false` restores source-backed reads and stops the worker. Leave V31 in place during application rollback.
