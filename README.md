# LUMA · Physics Cube Academy

**Status (17 Nov 2026): complete and tested end to end for one academy; not yet deployed or used with real students.**
Deployment, the live pilot and sign-off are the academy's (`docs/delivery/PILOT-PLAN.md`, `GO-LIVE-CHECKLIST.md`). What
it deliberately does not do: [docs/delivery/KNOWN-LIMITS.md](docs/delivery/KNOWN-LIMITS.md).

A course- and batch-aware Zoom Webinar attendance and engagement platform for Physics Cube Academy. What it does:

- **Accounts and access:** staff and student sign-in with email and password, no second factor (RS256 JWTs, rotating
  refresh sessions in HttpOnly cookies), invitations, password reset and password change, per-visitor and per-account
  rate limits, account lockout; roles scoped to the academy, assigned batches, or the student's own data on every route.
- **Academic data:** courses, batches, students (CSV import, or synced read-only from the Academy's own database by class),
  enrolments, staff assignments, bulk invitation emails, an append-only activity log.
- **Zoom owns schedules (Academy decision 2026-11-18):** staff add a webinar by its ID or join link only; LUMA reads its
  title, schedule and occurrences from Zoom (read-only) and has no scheduling, editing or import controls. Every run Zoom
  actually starts (early, late or unscheduled) becomes its own attendance class, keyed by the occurrence UUID, from the
  actual start to the actual end; a missed end is closed from Zoom's report. See `docs/integration/ZOOM-RULES.md`.
- **Less manual work:** a home page of what needs attention, an attendance dashboard by course, batch and period, every
  ready class finalised at once or automatically once Zoom's report is in.
- **Zoom:** signed webhook receipt (proven with a real test webinar), a retrying worker, participants matched to students by Zoom email (or an administrator's audited decision), Zoom's
  participant report fetched after each webinar (host marked, registrants matched by their registration email); "Check a
  webinar" by join link or ID shows any past run, even ones LUMA never received live; IP/device data is never stored.
- **Attendance:** provisional facts live (time connected with rejoins merged, lateness), then final present/late/absent
  from Zoom's report by the academy's own recorded rule; corrections with reasons, recalculation after a late report or a
  rule change, student appeals, email notices to students and managers, histories, per-student attendance
  rates per batch, academy totals per batch for analysts, CSV exports.
- **Engagement (off by default, consent-based):** portal reactions, instructor pulse questions, staff-only top 10/100 per class, batch
  or course, with CSV download.
- **Privacy:** an owner can erase a student on request (attendance kept, anonymised); a nightly job removes emails from
  old Zoom event payloads.
- **Operations:** Terraform for the AWS design with WAF, alarms and daily backups (validated in CI, not applied), tested
  restore drill, image builds in CI, load-test tooling, deploy/rollback/incident runbooks.

Evidence of every slice (PRs, CI results, test counts) is in [docs/ai/checkpoints/PROGRESS.md](docs/ai/checkpoints/PROGRESS.md).
Do not onboard real students until the academy's P0 decisions are signed off (`docs/product/OPEN-QUESTIONS.md`).

- GitHub: https://github.com/Suthankan1/luma-pca
- Figma: https://www.figma.com/design/QVzsewJyikM94Xp07hDwwi
- Brand: Physics Cube Academy, navy / blue / cyan / white.
- System design: [docs/architecture/overview.md](docs/architecture/overview.md)
- Full signed-off *proposal*, not completed functionality: [docs/architecture/full-production-blueprint.md](docs/architecture/full-production-blueprint.md)
- Agent onboarding: [AGENTS.md](AGENTS.md) and [docs/ai/START-HERE.md](docs/ai/START-HERE.md)

## Final technology decisions

| Layer | Technology |
| --- | --- |
| Frontend | Next.js 16 App Router, React 19, TypeScript, Tailwind CSS 4 (responsive staff/student web) |
| API/backend | Java 21, Spring Boot 4.1.2, modular monolith; later Spring Modulith tests |
| Authentication | **Implemented:** first-party RS256 JWTs, account lockout, rotating refresh sessions, Next.js HttpOnly session cookies, email and password sign-in for every role (no MFA), password change and reset, invitations by email, per-visitor rate limits, role/academy/batch/ownership checks on every route |
| Database | PostgreSQL 17, Flyway; schema evolves through reviewed migrations |
| Zoom | **Implemented:** signed webhooks into an idempotent inbox with a retrying worker, S2S OAuth read-only client, post-webinar participant reports |
| Async | **Implemented:** database inbox with SKIP LOCKED workers (Zoom receipts, invitation emails, reports, retention); dead letters with audited replay |
| Updates | **Implemented:** reconciliation with Zoom's report before final attendance; webinar and class pages refresh themselves while a run is live |
| Production | **As code, not applied:** AWS ECS/Fargate, RDS, S3, WAF, CloudWatch alarms, AWS Backup (Terraform validated in CI); needs the academy's budget and account |

## Prerequisites

- JDK **21** (set `JAVA_HOME` to it if another JDK is the default), a running Docker daemon for the PostgreSQL Testcontainers tests, Maven **3.9+** (`mvn`, or install wrapper before using `./mvnw`), Node.js **22** (minimum supported by Next.js is 20.9), npm, Docker Engine with Docker Compose v2.
- No cloud credentials or real student data are needed for the bootstrap.
- Local PostgreSQL credentials are sample-only and **must never be used in production**.

## Quick start (three terminals)

```bash
# In luma-pca/
cp .env.example .env                   # optional; review local values
docker compose up -d postgres           # wait for db health

# Terminal 2: backend
cd backend
LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true mvn spring-boot:run   # default API port 8080; throwaway token key
# GET http://localhost:8080/api/v1/system/status
# GET http://localhost:8080/actuator/health/readiness

# Terminal 3: frontend
cd frontend
cp .env.local.example .env.local
npm ci
npm run dev                            # http://localhost:3000
```

The backend exposes `POST /api/v1/auth/login`, `refresh`, `logout`, `logout-all` and `activate`, `GET /api/v1/auth/me` and `GET /.well-known/jwks.json`. The Next.js sign-in, session continuation and sign-out work against it (`scripts/auth-stack-smoke.sh`). Staff and students sign in with email and password only; there is no second factor. Create the first academy owner with `scripts/bootstrap-owner.sh "Academy name" owner@example.com` (after `mvn -f backend/pom.xml package`, with the same database settings as the backend); it prints an activation link. Everyone else is invited from the staff screens. The Spring security filter denies other API routes by default until authorized feature slices are built.

## Commands

```bash
mvn -f backend/pom.xml test
scripts/backend-test-docker.sh test     # the same tests in a throwaway Maven container (JDK 21 inside; host Java and settings untouched)
npm --prefix frontend run lint
npm --prefix frontend run build
python3 scripts/verify_structure.py
scripts/auth-stack-smoke.sh            # real backend + database + Next.js sign-in flow
scripts/staff-ui-real-stack.sh         # real stack + Playwright, end to end (also the "Real-stack browser test" CI workflow)
scripts/bootstrap-owner.sh "Academy" owner@example.com   # create an academy's first owner (operator command)
STUDENTS=150 scripts/load-test-local.sh   # k6 load scenarios on this machine (baseline only; see docs/testing/LOAD-TEST-PLAN.md)
```

## Repository anatomy

```text
luma-pca/
├── AGENTS.md, CLAUDE.md, .github/copilot-instructions.md
├── frontend/                  # Next.js / responsive preview and auth/BFF boundaries
├── backend/                   # Spring Boot API, feature package boundaries, initial Flyway
├── docs/
│   ├── architecture/          # full prior blueprint, concise architecture, ADRs
│   ├── product/               # PRD, requirements, personas, backlog, questions
│   ├── security/              # first-party JWT design, permissions, threat model
│   ├── integration/           # Zoom feasibility, webhook/reconciliation constraints
│   ├── database/              # data model, consistency and migration policies
│   ├── diagrams/              # editable Mermaid sources
│   ├── api/                   # API conventions and OpenAPI planning
│   ├── testing/               # test pyramid and quality gates
│   ├── delivery/              # slices, release criteria, incident runbooks
│   ├── ai/                    # agent onboarding, prompts, continuation checkpoints
│   └── research/              # prior research with citations and source caveats
├── contracts/                 # OpenAPI, examples, schema formats
├── references/                # legacy HTML demo (NOT the production app), images
├── infra/terraform/           # proposed deployment layout, NOT provisioned
├── scripts/                   # local verification helpers
├── .github/workflows/         # initial CI jobs
├── docker-compose.yml         # Postgres for local development
└── .env.example
```

## Development protocol

1. Read `docs/ai/START-HERE.md`, `AGENTS.md`, `docs/product/PRD.md`, and relevant ADRs before coding.
2. Pick **one small vertical slice** from `docs/delivery/IMPLEMENTATION-PLAN.md`; record a measurable done criterion.
3. Write tests first when it adds clarity; include cross-batch authorization, DB integration, negative paths, failure/replay tests.
4. Run relevant checks; update docs and `docs/ai/checkpoints/PROGRESS.md` after the slice; commit separately.
5. **No invented Zoom API capabilities:** native webinar attendee emoji identity is not proven. Use academy portal events for identifiable reactions.
6. Never enable real production access without `docs/delivery/GO-LIVE-CHECKLIST.md` approval.

## Boundaries

- **Connection time ≠ attention.** Attendance comes from authorized Zoom join/leave/report evidence, not student portal logins.
- Course → Batch → Enrollment and ClassSession ↔ SessionBatch are **day-one** requirements. Students may belong to multiple batches, and a shared session is not duplicate attendance.
- Keep the roles and batch/course scopes enforced in the backend; UI filtering is not an access-control mechanism.
- Secure webhook verification and durable acknowledgements precede any live Zoom connection.
- No real passwords, private keys, Zoom secrets, tokens, or student PII in this repository or demo references.

## Licensing

Private repository; no software license has been selected. See `LICENSE_DECISION_REQUIRED.md` before publishing reusable source code or onboarding external contributors.
