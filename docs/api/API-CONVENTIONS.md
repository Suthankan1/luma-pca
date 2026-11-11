# REST API conventions and contract governance

- Prefix `/api/v1/` for domain routes; `/actuator/health/readiness` is operational.
- JSON uses ISO-8601 timestamps with `Z`/offset, stable UUID IDs, explicit status enums, cursor pagination for large datasets, consistent `ProblemDetail` errors.
- Roles and academy/course/batch scopes are server-enforced per route; path IDs are untrusted. No endpoint may return raw Zoom secrets.
- State-changing operations use authenticated BFF CSRF measures, request IDs and audit records. For critical mutators include idempotency keys.
- Never turn tentative architecture endpoints into documented deployed functionality until tests prove them.
- `contracts/openapi/platform-v1.yaml` is a **planning contract**, not generated from the current app. Implemented: `GET /api/v1/system/status`, `POST /api/v1/auth/login`, `POST /api/v1/auth/activate`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/logout-all`, `GET /api/v1/auth/me`, `GET /.well-known/jwks.json`, and `GET`/`POST` on `/api/v1/courses` and `/api/v1/batches` with `GET`/`PUT` on `/{id}`. Updates send the `version` last read and get 409 if it is stale. A record in another academy returns 404, never 403. Small academy-bounded lists (courses, batches) are returned whole; cursor pagination applies to large datasets. Account/enrollment/batch/webinar/attendance routes are planned.
- Use `docs/diagrams/zoom-events.mmd` and `docs/security/JWT-AUTH.md` for the trust boundaries.
