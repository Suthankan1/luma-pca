# Backend starter

Spring Boot 4.1.2 / Java 21 / PostgreSQL 17. Bootstrap capabilities today:

- `GET /api/v1/system/status` — public non-sensitive status with `BOOTSTRAP_ONLY` stage.
- `/actuator/health/readiness` — reads PostgreSQL health (requires local Docker DB).
- Flyway V1 creates `academies` and `courses`. Additional domain tables are planned, not built.
- All other paths are **deny-by-default** in `SecurityConfig`. There is **no functioning login or JWT issuer yet**.
- `AttendanceDurationCalculator` and four unit tests implement the interval union/clamp **primitive only**; no webhook processing or final attendance currently exists.

```bash
cd .. && docker compose up -d postgres
cd backend && mvn test && mvn spring-boot:run
```

See `docs/security/JWT-AUTH.md` for accepted security design, `docs/integration/ZOOM-RULES.md` for Zoom gating, `backend/AGENTS.md` for implementation rules. For production, move DB secrets to a managed store and configure signed JWT keys with rotation; do not use these local values.
