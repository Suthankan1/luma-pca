---
applyTo: "backend/**"
---
# Backend instructions
Java 21, Spring Boot 4.1.2, modular monolith. Follow `backend/AGENTS.md`. Migrations via Flyway. Default deny; role + resource scope checks. Integration tests against PostgreSQL Testcontainers, with idempotent Zoom receipt handling once implemented. Never expose raw student rows through a general staff endpoint without scoped permission.
