# Architecture diagrams (editable Mermaid source)

- `context.mmd` — user systems, Next BFF, Spring API, Postgres, Zoom and queue.
- `domain-erd.mmd` — course/batch/student/session/attendance core domain.
- `auth-flow.mmd` — login + refresh + JWT validation; **planned**.
- `zoom-events.mmd` — verified webhook durable ingress + reconciliation; **planned**.
- `deployment.mmd` — illustrative AWS infrastructure proposal; **not deployed**.

View in GitHub Markdown Mermaid code fences, VS Code Mermaid plugins or https://mermaid.live (do not paste secrets or production PII into third-party editors). Diagrams are source-of-truth design artifacts; implementation status lives in `README.md` and `docs/ai/checkpoints/PROGRESS.md`.
