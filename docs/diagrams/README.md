# Architecture diagrams (editable Mermaid source)

- `context.mmd` — browsers, Next BFF, Spring API with its background workers, PostgreSQL, Zoom and mail (as built).
- `domain-erd.mmd` — course/batch/student/session/attendance core domain.
- `auth-flow.mmd` — email and password login + refresh + JWT validation (as built; no MFA).
- `zoom-events.mmd` — verified webhook durable ingress + reconciliation (as built).
- `deployment.mmd` — the AWS design in `infra/terraform`; **not applied**.

View in GitHub Markdown Mermaid code fences, VS Code Mermaid plugins or https://mermaid.live (do not paste secrets or production PII into third-party editors). Diagrams are source-of-truth design artifacts; implementation status lives in `README.md` and `docs/ai/checkpoints/PROGRESS.md`.
