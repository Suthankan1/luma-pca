# Persistent development checkpoint — LUMA

## Checkpoint 000A — 2026-11-11 (initial ZIP)

**Repository:** `Suthankan1/luma-pca` was inspected and found empty at bootstrap creation. The ZIP is a local deliverable; it has **not** been pushed to GitHub.

**Decided:** Next.js 16 / TypeScript frontend; Java 21 Spring Boot 4.1.2 modular monolith; PostgreSQL/Flyway; first-party signed JWT access + rotating opaque refresh sessions and Next secure BFF; multiple courses and batches from day one; Zoom S2S OAuth + webhooks and eventual official-report reconciliation; SQS/worker and AWS as production plan subject to costs.

**Included code:** Next.js home/staff/student preview pages using supplied academy logo and theme; Java Spring Boot app with public bootstrap status route, default-deny other API paths and local DB/health config; Flyway V1 academy/course tables; attendance union/clip domain primitive and JUnit tests; Docker Compose PostgreSQL. **Running status:** local builds/tests must be verified in each developer environment; see validation summary in root README if added.

**Included knowledge:** Full prior 2026-11-11 production architecture and feasibility research, PRD, user stories, unresolved questions, role matrix, JWT and Zoom rules, ADRs, Mermaid context/ERD/sequences/deployment, delivery plan, test strategy, runbooks, CI setup, prompts and agent instructions, legacy HTML demo as non-production visual reference.

**Explicitly not implemented:** real login/invitation/JWT signing/BFF sessions, full academic CRUD, student records, Zoom OAuth/webhook processing, attendance persistence/finalization, SQS worker, SSE, actual reporting, infrastructure or production deployment. All demo metrics fictional.

**Validation:** See `docs/delivery/INITIAL-VALIDATION.md`. Structure/XML/JSON/YAML checks and Java 21 domain smoke pass. Full Maven, Next, Docker integration tests not run due missing Maven/Docker and unavailable registry DNS.

**Next tiny steps:** (1) verify `mvn test` and Next `npm install && npm run lint && npm run build`, Docker PostgreSQL migration and API status; (2) commit/bootstrap; (3) start `001A` persistence-backed account model, password-hash tests and migration without issuing JWT yet.

**Agent update rule:** After each completed small feature, append its feature ID, commit SHA if known, actual test commands/results, decisions, scope changes, next intended slice and unresolved blockers. Never overwrite verified history or claim a commit/push without evidence.
