# ADR-0001 — Modular Spring Boot monolith
**Status:** Accepted · 2026-11-11
**Context:** Multi-course, multi-batch academy needs transactional consistency, scope-based authorization and evolving business features with a small team.
**Decision:** Single Java 21/Spring Boot 4.1.2 codebase with independent packages for domains. Start one API process; introduce a worker runtime on the same shared code when async workflows are implemented. PostgreSQL per application, module-owned tables and bounded application interfaces. Use Spring Modulith/ArchUnit verification in a later slice.
**Rejected now:** Multiple microservices, Kafka, Kubernetes, GraphQL and Go webhook worker. The problem does not justify independent deployables yet.
**Consequences:** Simpler local dev and transactions; explicit package/module architecture and tests are mandatory to prevent a big-ball-of-mud. Extract services only against measured scaling, compliance or team boundaries.
