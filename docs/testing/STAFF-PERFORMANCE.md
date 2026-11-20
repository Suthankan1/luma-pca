# Staff performance checklist

Progress is the number of verified local items divided by the ten local items below. Hosted acceptance is tracked separately. A passing mock or local synthetic run does not establish Supabase or production performance.

| # | Local slice and acceptance check | Done |
| --- | --- | --- |
| 1 | Timing and current-route load coverage. Verify response headers and benchmark configuration. | Verified |
| 2 | Run a reproducible signed-in synthetic baseline and record request errors and route p95. | Verified |
| 3 | Batch Classes attendance facts. Verify expected, joined and academy scope and compare query work. | Verified |
| 4 | Stream Classes, Students and Class Types with stable fallbacks and independent failure handling. | Verified |
| 5 | Add and verify a durable, replay-safe completed attendance summary. Prove parity and rollback before enabling reads. | Pending |
| 6 | Add a scoped Students monthly read with full-result filtering, ordering and aggregates. | Pending |
| 7 | Poll live data independently of the page and preserve timeline recovery and completion. | Pending |
| 8 | Load Reports options on demand and verify all export types. | Pending |
| 9 | Measure auth overhead and verify any auth or cache changes against logout and account switching. | Pending |
| 10 | Automate regression checks, repeat the baseline and document release and rollback. | Pending |

## Measurement

- `Server-Timing: next-proxy` measures Next.js request routing and silent renewal. It does not include React rendering or streamed panel time.
- `Server-Timing: api` is API time up to setting the response headers, including authentication. Record full request duration separately, especially for downloads.
- Use synthetic data, a production Next build, and the staff k6 scenario from the load-test guide. Record dataset size, date window, concurrency, failures, p50/p95/p99 and the commit for each comparison. Check meaningful content as well as HTTP status.
- Measure click feedback, shell and panel content separately in a browser. Warm target budgets are 100 ms for feedback, 200 ms for the shell and 500 ms p95 for the attendance API. Track browser LCP, INP and CLS at p75 in the intended environment.

## Synthetic baseline

Windows Docker Desktop, production Next.js build and backend `904e345`. Run `scripts/load-test-local.sh` with `STUDENTS=150`, `STAFF=4`, `STAFF_DURATION_S=45` and `SCENARIOS=staff`. The isolated fixture uses 28 completed classes from the previous Sri Lanka month, three class types and 150 synthetic Zoom report rows per class. Use `LUMA_LOAD_PYTHON=python` on systems where Python is installed under that command.

| Page | Samples | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: |
| Classes | 8 | 303 ms | 392 ms | 394 ms |
| Class Types | 8 | 258 ms | 412 ms | 464 ms |
| Students | 8 | 471 ms | 658 ms | 664 ms |
| Session detail | 8 | 114 ms | 220 ms | 236 ms |
| Report selection | 8 | 51 ms | 78 ms | 82 ms |

All 60 requests passed. The busiest SQL was the student identity lookup with 680 executions and 21.8 seconds total across requests. The short run provides a development comparison with eight samples per page. The API access-log percentile parser did not run because `python3` was unavailable, and the run did not measure browser vitals, Excel export, or original Supabase latency.

## Batched Classes comparison

The same fixture and frontend build with backend `46a4a6a` passed all 60 requests again. Classes p95 fell from 392 ms to 185 ms and Class Types from 412 ms to 131 ms. The report-participant SQL and the matched-student SQL each dropped from 680 to 264 executions across the run. Classes and Class Types share the affected totals endpoint, which returned at p95 139 ms over 16 requests. The run still used the existing Students dashboard read path. These short measurements are local evidence and should be repeated in the intended environment.

## Hosted acceptance

- [ ] Confirm query plans, connection wait and latency in an approved Supabase environment. Verify a same-region runtime and pool sizing under concurrent load before changing either.
- [ ] Accept measured performance and resource budgets with representative traffic and enable sustained API latency, pool and worker-lag alerts.
- [ ] Verify attendance parity with the academy and follow the reviewed migration, rollout and rollback steps before enabling new reads.
