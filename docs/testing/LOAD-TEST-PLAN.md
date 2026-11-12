# Load and capacity test plan — 1,500 concurrent users

**Status:** plan, tooling and one local baseline (section 9). **No capacity claim is made by this document.** LUMA must not be
described as supporting 1,500 users until the gate scenarios below pass in a
production-like environment and the results are recorded in the repository checkpoint.

## 1. What "1,500 users" means here (assumptions to confirm)

The academy has not yet given real numbers (see `docs/product/OPEN-QUESTIONS.md`, P0). Until
it does, the target is the brief's figure, modelled as the worst realistic moment: **one
class of 1,500 students starting at once**. Each item below needs the academy's confirmation;
the scripts take every number as a parameter.

| Assumption | Value used | Why it matters |
| --- | --- | --- |
| Students in the largest class | 1,500, one Zoom webinar | Size of every burst below |
| Sign-ins at class start | 1,500 within 10 minutes, half in the first 3 | Argon2id hashing is deliberately expensive (19 MiB, 2 passes) |
| Already signed in | 0% (worst case; a refresh is far cheaper than a sign-in) | |
| Shared addresses | Many students behind mobile carrier NAT | Per-visitor limits (001L) must not block a class |
| Zoom events | 1,500 joins in 3 min; 20% reconnect once (leave + join); 1,500 leaves in 2 min at the end | Webhook ingress and the receipt worker |
| Student activity during class | Portal page every 60 s on average | Session refresh every 10 min |
| Staff | 20 staff reading rosters, classes and Zoom review | Heavier queries |
| Other classes at the same time | None in the gate scenario; a mixed scenario adds 3 × 300 | |

## 2. Service levels to pass (proposed, to agree)

| Flow | Target |
| --- | --- |
| Sign-in through the web app (form post to redirect) | p95 ≤ 2.0 s, p99 ≤ 4.0 s during the burst |
| Student portal page and API reads | p95 ≤ 500 ms, p99 ≤ 1.0 s |
| Staff roster, classes, Zoom review | p95 ≤ 1.0 s |
| Zoom webhook acknowledgement | p99 ≤ 1.0 s (Zoom expects an answer within seconds and retries 5xx); **zero 5xx** |
| Webhook to processed (worker drain) | backlog from the join burst fully processed ≤ 5 min after the burst ends |
| Errors | < 0.1% of requests, excluding deliberate 429s; no 429 for a class signing in from distinct addresses |
| Resources | API CPU ≤ 70% sustained, DB CPU ≤ 70%, no connection-pool exhaustion, no restarts, heap stable over the soak |
| Correctness | every webhook stored exactly once; every sign-in session valid; no deadlocks or lock timeouts in the logs |

## 3. Scenarios

| ID | Scenario | Status | Script |
| --- | --- | --- | --- |
| L1 | **Class-start sign-in burst:** 1,500 students sign in through the Next.js BFF (form post, cookies, redirect) with distinct client addresses, then open the student portal | Testable now | `load-tests/k6/sign-in-burst.js` |
| L2 | **Webhook burst:** signed `participant_joined` for 1,500 participants in 3 min, 20% reconnects, then 1,500 `participant_left` in 2 min; measure ack latency and worker drain | Testable now (fixture payloads, not a real Zoom account) | `load-tests/k6/webhook-burst.js` |
| L3 | **Staff during class:** 20 staff reading rosters, the class, expected students and Zoom review while L1 and L2 run | Testable now | `load-tests/k6/staff-reads.js` |
| L4 | **Combined gate:** L1 + L2 + L3 together | Testable now | run the three together |
| L5 | **Soak:** 1,500 signed-in students at steady activity for 60 min | Testable now | `sign-in-burst.js` with a long hold |
| L6 | **Live attendance updates** (SSE) for staff during class | Blocked: SSE not built | — |
| L7 | **Attendance finalisation** for 1,500 students after class | Blocked: attendance (004A) waits on 003A | — |
| L8 | **Reports and exports** | Blocked: not built | — |

The gate for any "supports 1,500 users" statement is **L4 and L5 passing in a
production-like environment**, then **L6 and L7** once those features exist.

## 4. Environments

1. **Local (this repository, any developer machine):** `scripts/load-test-local.sh`
   starts PostgreSQL 17, the backend jar and a production Next.js build, seeds the data and
   runs the k6 scripts in Docker (`grafana/k6:1.4.2`). Its purpose is to make the scripts
   trustworthy and to find gross bottlenecks. **Its numbers are a laptop baseline, never
   capacity evidence:** the load generator, both servers and the database share one machine.
2. **Production-like (required for the gate):** the planned AWS shape (ECS for API and web
   with the intended task sizes and counts, RDS PostgreSQL 17 of the intended class, load
   balancer, `LUMA_TRUSTED_PROXIES` and `LUMA_TRUSTED_PROXY_HOPS` set), with the load
   generator on separate hosts in the same region. **Needs the academy's authorisation for
   chargeable cloud resources; not provisioned.** Run, then tear down.

## 5. Data

Synthetic only, never real student data: `load.studentN@example.invalid`, generated names,
one course, three batches, one class session on webinar `81234567890`. Students share one
test password; the seed copies a single Argon2id hash (computed by activating one account
through the real API) to all accounts, so seeding 1,500 students takes seconds while every
sign-in still verifies a real Argon2id hash. The first owner is created with
`scripts/bootstrap-owner.sh`.

Client addresses for L1 come from the RFC 5737 documentation ranges, one per student, sent
as `X-Forwarded-For` with the web app trusting one proxy hop: this models students on
distinct connections. A separate L1 variant with 50 students per address models carrier NAT.

## 6. Measurements to record for each run

k6 summary per flow (count, p50/p95/p99, failures by status), webhook receipts by status
and the time until none are `RECEIVED`, PostgreSQL connections and slow statements, API
and web CPU and memory (`docker stats` locally; CloudWatch in AWS), JVM heap and GC
pauses, and every ERROR/WARN line in the logs. Results go into
`docs/ai/checkpoints/PROGRESS.md` with the environment description; a local run is always
labelled as a local baseline.

## 7. Known risks the tests should expose

- **Argon2id cost:** each sign-in hashes 19 MiB twice. 1,500 sign-ins in a few minutes is
  CPU- and memory-heavy; the API task size and Hikari pool must be sized from L1.
- **Worker drain rate:** the receipt worker handles at most 50 receipts per 5-second poll
  (`LUMA_ZOOM_PROCESSING_BATCH_SIZE`, `LUMA_ZOOM_PROCESSING_POLL_INTERVAL`), about 10 per second per
  instance, so a 1,500-join burst needs roughly 2.5 minutes to process. Within the 5-minute
  target, but L2 must confirm, and the settings may need tuning.
- **Per-visitor limits:** 60 sign-ins per 5 minutes per address. A school or carrier NAT
  sending more than that from one address would be refused; the NAT variant of L1 measures
  how real that is, and the limit is configurable.
- **Session refresh churn** and **connection pool size** (Hikari default 10) under L5. The
  pool has since been ruled out as a bottleneck at the plan's peak (section 10).

## 8. Exit criteria and reporting

A run passes when every target in section 2 holds for the whole run and the correctness
checks find nothing. A failed target is a finding to fix and re-run, not a number to quote.
Only a passing production-like L4 + L5 run may be cited, with its date, environment and
commit, as evidence for 1,500 concurrent users on the features tested.

## 9. Local baseline results (not capacity evidence)

**2026-11-12**, commit `884725b` plus this change, on one Apple M4 laptop (10 cores, 16 GB,
macOS 27.2): API (JVM, default heap), Next.js production server and k6 on the host or in
Docker Desktop (4 CPUs, 4 GB VM), PostgreSQL 17 in Docker. `STUDENTS=1500`, all three
scenarios together with the plan's windows (10-minute sign-in window, 3-minute join burst).

| Flow | Requests | p50 | p95 | p99 | Failures |
| --- | --- | --- | --- | --- | --- |
| Sign-in through the web app | 1,500 | 32 ms | 62 ms | 71 ms | 50 connection timeouts (see below) |
| Student portal page | 4,350 | 4 ms | 11 ms | 20 ms | 132 connection timeouts |
| Zoom webhook acknowledgement | 3,600 | 2 ms | 7 ms | 14 ms | 0 |
| Staff class page (1,500 expected students) | 832 | 316 ms | 446 ms | 634 ms | see below |
| Staff roster (500 students) | 832 | 119 ms | 178 ms | 260 ms | see below |
| Staff class list | 832 | 12 ms | 29 ms | 43 ms | see below |

- **Worker:** all 3,600 receipts processed, each stored once (3,600 occurrence events); the
  backlog was empty when the last event arrived (the burst rates stayed under the worker's
  ~10 per second).
- **Peak CPU (5 s samples):** API 107% (about one core), web 36%, database 32%. No ERROR
  lines in the backend log.
- **Connection timeouts (182, 3.1% of student requests):** k6 got "dial: i/o timeout"
  before any HTTP exchange; server-side latencies stayed low and the servers logged no
  errors. Suspected local causes: macOS's listen backlog (`kern.ipc.somaxconn` 128, against
  Linux's 4096) and Docker Desktop's forwarding to the host. **Resolved:** the same
  1,500-student run on Linux (below) had none, so they belong to the local harness.
- **Staff sign-in redirects (35):** the run lasts longer than the 10-minute access token and
  the first version of `staff-reads.js` did not refresh the session as the browser's
  "Continue session" does. Fixed in the script; a re-run with 1-minute access tokens
  (`ACCESS_TTL=PT1M`, 150 students) refreshed 40 sessions with no failures.
- **Earlier script defects found while preparing this (not product defects):** the `Origin`
  header must be the web app's configured origin, not the address k6 connects to; k6 clears
  cookies at each iteration unless `noCookiesReset` is set.

**Linux runner, 300 students (2026-11-12, commit `180edb3`).** The manual "Load test (local
baseline)" workflow ([run 43194535303](https://github.com/Suthankan1/luma-pca/actions/runs/43194535303))
on one GitHub `ubuntu-24.04` runner, all three scenarios, windows scaled to 300 students:

| Flow | Requests | p50 | p95 | p99 | Failures |
| --- | --- | --- | --- | --- | --- |
| Sign-in through the web app | 300 | 71 ms | 265 ms | 457 ms | 0 |
| Student portal page | 900 | 10 ms | 64 ms | 191 ms | 0 |
| Zoom webhook acknowledgement | 720 | 4 ms | 55 ms | 148 ms | 0 |
| Staff class page | 171 | 89 ms | 463 ms | 878 ms | 0 |
| Staff roster | 171 | 47 ms | 221 ms | 313 ms | 0 |

All 720 receipts processed once with no backlog; API peak about 1.5 cores, web 41%,
database 15%; no ERROR lines; no connection timeouts. This run does **not** settle the
1,500-student timeouts above: the laptop also had none at 150 students, so the question
needs the same 1,500-student run on Linux (about 20 runner minutes). No session refresh
happened in this short run (`refresh` count 0), so its threshold passed without data.

**Linux runner, 1,500 students (2026-11-12, commit `9b5164e`).** Same workflow
([run 43195499238](https://github.com/Suthankan1/luma-pca/actions/runs/43195499238)), the
plan's full windows, all three scenarios on one 4-vCPU `ubuntu-24.04` runner. **Failed its
latency targets.**

| Flow | Requests | p50 | p95 | p99 | Failures |
| --- | --- | --- | --- | --- | --- |
| Sign-in through the web app | 1,500 | 2.48 s | 6.93 s | 7.42 s | 0 (target p95 ≤ 2 s missed) |
| Student portal page | 4,500 | 774 ms | 6.56 s | 7.07 s | 0 (target p95 ≤ 500 ms missed) |
| Zoom webhook acknowledgement | 3,600 | 6 ms | 41 ms | 132 ms | 0 (met) |
| Staff class page (1,500 expected students) | 651 | 948 ms | 3.79 s | 7.52 s | 2 HTTP 500, 1 "couldn't load" page |
| Staff roster | 650 | 336 ms | 2.53 s | 4.15 s | 0 |
| Staff class list | 657 | 43 ms | 1.48 s | 2.19 s | 0 |

- **The laptop's connection timeouts did not occur on Linux** at the same scale (0 of 6,000
  student requests failed). They are therefore attributed to the local macOS / Docker
  Desktop harness, and that question is closed.
- **Webhooks and the worker held up:** every event acknowledged (p99 132 ms), all 3,600
  processed once with no backlog.
- **Everything else slowed sharply.** Peak CPU: API 168%, web 56%, database 63%, while three
  k6 load generators shared the same 4 vCPUs. The run saturated one small machine; **which
  tier limits throughput was not isolated** (sign-in hashing runs outside database
  transactions, so connection-pool starvation by Argon2id is ruled out; the pool is
  Spring's default of 10 and remains a suspect).
- **Staff page errors under saturation:** one class page showed the app's own "couldn't
  load" message (its 5-second API timeout tripped, a graceful outcome), and two returned
  HTTP 500 with no backend ERROR lines, so they came from the web tier; the script did not
  capture the web log then. It now prints a masked summary of web-log errors.
- **227 failed staff sign-ins were a script defect:** after a failed page the script signed
  in again while still holding a session, and the web app (correctly) sent it to "Continue
  session" instead; the script never recovered. It now clears its cookies before signing in
  again. This fix is reasoned, not yet exercised at 1,500.

**What this shows:** the scripts work end to end. A single 4-vCPU machine hosting the load
generator, both servers and the database cannot meet the targets for a 1,500-student class
start. That is expected for this setup and says nothing yet about the production sizing.
**Next diagnostic step:** run the load generator on a separate machine from the servers,
record per-tier timings (API request durations, web render time, database waits), and
try a larger Hikari pool, before the production-like gate run. **What it does not show:** that LUMA supports 1,500 users. That needs sections 3 and
8 in a production-like environment.

## 10. Bottleneck analysis (2026-11-12)

The 1,500-student Linux run missed its latency targets on one 4-vCPU runner. To find out
why without more runner time, each flow was measured alone on the laptop (which has spare
capacity) with per-tier instrumentation now built into `scripts/load-test-local.sh`: CPU
seconds per tier, the API's own per-route times (Tomcat access log: method, path, status
and milliseconds only), the busiest database statements (`pg_stat_statements`) and sampled
busy database connections.

**CPU per request (M4 core-milliseconds):**

| Request | API | Web | Database |
| --- | --- | --- | --- |
| Sign-in (Argon2id hash + session) | 39.5 | 3.4 | 4.1 |
| Student portal page (`/auth/me` + render) | 1.4 | 3.0 | 0.5 |
| Staff page, average of the four (before the fix below) | 35.9 | 16.3 | 18.0 |
| Staff page, average of the four (after the fix) | 34.0 | 20.6 | 7.8 |

**Finding 1: an N+1 query, fixed.** Rosters and the class page load each student's
sign-in account; every account's roles were then fetched with one query each: 522,020
role queries in three minutes of staff reads, about 1,000 per page. Hibernate batch
fetching (`hibernate.default_batch_fetch_size: 100`) cut that to 5,260; class-students API
p50 290 → 46 ms, roster API p50 110 → 27 ms, class page end to end p50 320 → 84 ms (p95
440 → 110 ms), database CPU for the same work 19.2 → 8.4 s. All 287 backend tests pass.

**Finding 2: the database pool is not a bottleneck.** No request spent more than about
2 ms in the database, so even the peak request rate keeps the 10-connection pool well
under 1% busy; sampled busy connections never exceeded 1. (The samples are every 5 seconds,
so the conclusion rests on the measured database time, not the samples.)

**Finding 3: the runner was CPU-saturated.** At the plan's peak (sign-ins ramping to 8.3
per second, about 25 portal views per second, 20 staff reading continuously) the app tiers
need about **0.9 M4 cores** (API 0.56, web 0.22, database 0.09). Sign-ins take about 45% of
that, staff pages 41%, portal views 14%. On the runner each tier used about 3× its predicted
M4 CPU (API 3.0×, web 2.9×, database 4.2× before the fix), i.e. roughly 2.9 of its 4 vCPUs,
while three k6 processes with up to 1,500 virtual users ran on the same vCPUs. The API's
Argon2id hashing is deliberately expensive and is not changed; it must be sized for.

**Not measured:** the webhook worker's CPU on its own (its acknowledgements stayed fast in
every run) and the k6 processes' CPU on the runner. The 3× factor is inferred from the
runner's per-tier peaks, not benchmarked directly.

**Implications for the production-like gate run:** put the load generator on separate
hosts; size the API for the sign-in burst first (on runner-class vCPUs, about 1 vCPU for
hashing at 8 sign-ins per second, plus about 0.6 for staff pages and sessions, with
headroom: at least 2 vCPUs, or two tasks); the web tier at about 1 vCPU; the database
lightly loaded. These are estimates from this model to be confirmed by that run. **Still no
capacity claim.**

### Re-run after the fix (2026-11-12, commit `b35019d`)

Same workflow and settings as the failed run: 1,500 students, all three scenarios, one 4-vCPU
`ubuntu-24.04` runner ([run 43209730397](https://github.com/Suthankan1/luma-pca/actions/runs/43209730397)).
**Every target in section 2 was met, with no failures.**

| Flow | p95 before the fix | p95 after | p99 after | Failures after |
| --- | --- | --- | --- | --- |
| Sign-in through the web app | 6.93 s | 144 ms | 281 ms | 0 of 1,500 |
| Student portal page | 6.56 s | 48 ms | 110 ms | 0 of 4,500 |
| Staff class page (1,500 expected students) | 3.79 s | 335 ms | 1.17 s | 0 of 855 |
| Staff roster | 2.53 s | 143 ms | 517 ms | 0 of 855 |
| Zoom webhook acknowledgement | 41 ms | 10 ms | 26 ms | 0 of 3,600 |

All 3,600 receipts processed once with no backlog; 11 staff sessions refreshed; peak CPU API
159%, web 44%, database 15% (was 63%); 162.9 / 115.9 / 34.9 CPU seconds over 619 s; no
ERROR lines in either log.

**Correction to the model above:** the fix helped far more than the laptop costs
predicted. Those counted only the database CPU of the extra role queries; on the slower
runner, about 1.7 million extra round trips also cost API time and waiting, and pushed the
shared machine into saturation. Removing them, not extra capacity, is what brought the
targets within reach.

**What this does and does not show.** The combined class-start scenario (L4) now meets its
targets even with the load generator, both servers and the database on one 4-vCPU machine.
It is still not the gate in section 8: that needs a production-like environment with the
load generator on separate hosts, the 60-minute soak (L5), and later the live-attendance and
finalisation scenarios (L6, L7). **LUMA is still not described as supporting 1,500 users.**
