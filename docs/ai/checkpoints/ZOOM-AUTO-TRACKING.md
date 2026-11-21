# Zoom webinar auto-tracking

## Acceptance criteria

An approved academy host schedules a webinar in Zoom and starts the same link early, late, or on another day. LUMA tracks it automatically, records a separate run per Zoom occurrence UUID, and shows the actual run under the mapped Class Type. A scheduled slot alone does not generate absences.

## Work log

### 2026-11-21 — Original database baseline

- Started the Spring Boot and Next.js builds from `4b99f57` on loopback. The API uses the original Supabase configuration and listens on port 8088 because port 8080 is used by another application. The frontend on port 3000 points to port 8088.
- Flyway is disabled for this baseline startup so automatic schema changes do not run.
- Verified process startup, PostgreSQL connection-pool startup, API health and readiness (`UP`), sign-in page (200), and anonymous attendance redirect (307). No signed-in student or live Zoom webinar was tested.

## Remaining slices

1. Verify approved Zoom host coverage and webinar listing permissions. Add paginated schedule discovery and reuse the existing tracker and Class Type mapping. Persist opt-outs so a sync cannot restart monitoring.
2. Cover starts before the first schedule sync. Persist verified webhook receipts, verify the academy host, and resolve the webinar by ID before processing its occurrence. Reuse existing report reconciliation.
3. Test early and late starts, another-day runs, duplicate deliveries, missing mappings, and academy isolation. Run a licensed Zoom test webinar before enabling auto-tracking for approved academy hosts.

Use an opt-in setting for rollout. Disable it to stop auto-discovery while retaining recorded runs and manual tracking. Record changes, checks, and pilot evidence here as each slice finishes.
