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
- **Session refresh churn** and **connection pool size** (Hikari default 10) under L5.

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
  Linux's 4096) and Docker Desktop's forwarding to the host. **Unexplained until the same run
  passes on Linux** (the manual "Load test (local baseline)" workflow, or the
  production-like environment); not to be dismissed.
- **Staff sign-in redirects (35):** the run lasts longer than the 10-minute access token and
  the first version of `staff-reads.js` did not refresh the session as the browser's
  "Continue session" does. Fixed in the script; a re-run with 1-minute access tokens
  (`ACCESS_TTL=PT1M`, 150 students) refreshed 40 sessions with no failures.
- **Earlier script defects found while preparing this (not product defects):** the `Origin`
  header must be the web app's configured origin, not the address k6 connects to; k6 clears
  cookies at each iteration unless `noCookiesReset` is set.

**What this shows:** the scripts work end to end and nothing gross broke at 1,500 on one
laptop. **What it does not show:** that LUMA supports 1,500 users. That needs sections 3 and
8 in a production-like environment.
