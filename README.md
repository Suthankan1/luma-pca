# LUMA · Physics Cube Academy

**Status: architecture-approved project bootstrap, NOT a production-ready attendance system.**

A course- and batch-aware Zoom Webinar attendance and engagement platform for Physics Cube Academy, designed for real staff and students. The **implementation is intentionally incremental**: this archive provides working application shells, local PostgreSQL, an initial migration, a deterministic attendance calculation, tests, CI scaffolding, extensive technical/product documentation, diagrams, and AI-agent instructions. Accounts, Argon2id password hashing and activation invitations are persisted behind internal services, but backend JWT issuance/login, Zoom webhook ingestion, production attendance persistence, engagement and real deployment are **not implemented yet**. Do not onboard real students until the security and release gates pass.

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
| Authentication | **Planned:** first-party RS256 JWT access tokens; hashed rotating refresh-token sessions; Next.js HttpOnly BFF cookies; Spring Security RBAC + scope checks |
| Database | PostgreSQL 17, Flyway; schema evolves through reviewed migrations |
| Zoom | **Planned:** academy-owned S2S OAuth, REST reports, signed webhooks, idempotent event inbox |
| Async | **Planned:** transactional inbox/outbox, SQS + DLQ, separately scaled Spring worker |
| Updates | **Planned:** SSE for live dashboard; reconciliation before final attendance |
| Production | **Planned:** AWS ECS/Fargate, RDS, SQS, S3, monitoring, IaC, CI/CD |

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
mvn spring-boot:run                      # default API port 8080
# GET http://localhost:8080/api/v1/system/status
# GET http://localhost:8080/actuator/health/readiness

# Terminal 3: frontend
cd frontend
cp .env.local.example .env.local
npm ci
npm run dev                            # http://localhost:3000
```

There is **no backend login endpoint yet**: the Next.js sign-in flow is only tested against a mock API, so nobody can sign in against the real backend. The Spring security filter denies other API routes by default until authorized feature slices are built.

## Commands

```bash
mvn -f backend/pom.xml test
npm --prefix frontend run lint
npm --prefix frontend run build
python3 scripts/verify_structure.py
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

No software license has been selected. See `LICENSE_DECISION_REQUIRED.md` before publishing reusable source code or onboarding external contributors.
